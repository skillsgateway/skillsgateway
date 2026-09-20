package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.facade.HeldContentController;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The pull-side revocation check (GW_FACADE_0031, GW_FACADE_0032).
 *
 * <p>This is a trust boundary — it answers questions about the served estate to anyone holding a
 * token — so the tests here lean negative. The happy path is one assertion; what is worth attacking
 * is whether the answers a caller is <em>not</em> entitled to are genuinely indistinguishable from
 * the ones it is, whether the revocation reason stays out of the response, and whether the check
 * quietly writes a ledger row per poll.
 *
 * <p>{@code alice} is the base class's reviewer and {@code root} its second administrator, so this
 * suite adds no properties of its own and lands on the cached context every other suite uses —
 * {@code ContextBudgetTests} is the ratchet that says why.
 */
class HeldContentStatusTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A well-formed commit id that no fixture can have produced. */
    private static final String NEVER_HELD = "0".repeat(39) + "1";

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private ApprovalService approvalService;

    /**
     * What a holder is told about content it has: approved, approved-though-superseded, withdrawn,
     * and withdrawn-then-reinstated (GW_FACADE_0031).
     *
     * <p>Supersession is the one worth stating. A later approval does not retract an earlier one —
     * {@code refs/snapshots/<sha>} stays advertised — and the question a holder asks is whether it
     * may keep using what it has, not whether it is current. Answering {@code unknown} there would
     * have every client quarantine perfectly good content on the day a marketplace is updated.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0031"})
    void a_holder_is_told_approved_superseded_revoked_and_reinstated_apart() throws Exception {
        String pat = newPat();
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("held");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot first = approve(registered.snapshot().id());

        addUpstreamCommit(upstream, "second");
        Snapshot second = ingestionService.ingest(registered.marketplace(), null);
        approve(second.id());

        JsonNode answers = answers(pat, List.of(pair(name, first.sha()), pair(name, second.sha())));
        assertThat(state(answers, 0))
                .as("a superseded but still approved snapshot is approved, not unknown")
                .isEqualTo(HeldContentController.APPROVED);
        assertThat(state(answers, 1)).isEqualTo(HeldContentController.APPROVED);
        assertThat(answers.get(0).get("marketplace").asText()).isEqualTo(name);
        assertThat(answers.get(0).get("sha").asText())
                .as("the answer echoes the pair it answers, in the order asked")
                .isEqualTo(first.sha());

        revocationService.revoke(
                second.id(), "compromised dependency", RevocationService.ServeAfter.PREVIOUS_APPROVED, "root");
        answers = answers(pat, List.of(pair(name, second.sha())));
        assertThat(state(answers, 0)).isEqualTo(HeldContentController.REVOKED);
        assertThat(answers.get(0).get("revokedAt").isNull())
                .as("a withdrawal carries the time it happened")
                .isFalse();
        assertThat(answers.get(0).get("approvedOverReversedRevocation").asBoolean())
                .isFalse();

        // Reinstated: GW_APPROVAL_0017 requires that content served over a reversed withdrawal is
        // never indistinguishable from content nobody ever withdrew, and this is the surface most
        // likely to be read by a machine rather than a person.
        approvalService.approve(
                second.id(), "alice", ApprovalService.ApprovalOverride.ofRevocationReversal("dependency fixed"));
        answers = answers(pat, List.of(pair(name, second.sha())));
        assertThat(state(answers, 0)).isEqualTo(HeldContentController.APPROVED);
        assertThat(answers.get(0).get("approvedOverReversedRevocation").asBoolean())
                .as("an approval standing over a reversed withdrawal is distinguishable from one never withdrawn")
                .isTrue();
    }

    /**
     * The bound, and the refusal shape (GW_FACADE_0031). Refused whole rather than truncated: a
     * partial answer that looks complete is the failure a holder cannot detect, because every pair
     * it silently dropped reads as "no answer given" rather than "not asked".
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0031"})
    void an_oversized_request_is_refused_whole_rather_than_partly_answered() throws Exception {
        String pat = newPat();
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("bound");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());

        List<String> atTheBound = new ArrayList<>();
        for (int i = 0; i < HeldContentController.MAX_HOLDINGS; i++) {
            atTheBound.add(pair(name, approved.sha()));
        }
        assertThat(answers(pat, atTheBound)).hasSize(HeldContentController.MAX_HOLDINGS);

        List<String> oneOver = new ArrayList<>(atTheBound);
        oneOver.add(pair(name, approved.sha()));
        mockMvc.perform(check(pat, oneOver)).andExpect(status().isBadRequest());

        // Malformed input is refused the same way, and the refusal names neither the pair nor
        // which of its two fields was wrong.
        mockMvc.perform(check(pat, List.of(pair(name, "not-a-sha")))).andExpect(status().isBadRequest());
        mockMvc.perform(check(pat, List.of(pair("Not A Name", approved.sha())))).andExpect(status().isBadRequest());
    }

    /**
     * The disclosure half (GW_FACADE_0032). Every negative answer must be the same answer: a token
     * scoped to one marketplace cannot use the check as a directory of what else the gateway
     * governs, and no caller can probe the ingestion or retention history of content it was never
     * served.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0032"})
    void every_answer_a_caller_is_not_entitled_to_is_the_same_answer() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String mine = uniqueName("mine");
        String theirs = uniqueName("theirs");
        Registered ours = registerAndIngest(mine, upstream);
        Snapshot approved = approve(ours.snapshot().id());
        Registered other = registerAndIngest(theirs, createUpstream(DEFAULT_MANIFEST));
        Snapshot theirApproved = approve(other.snapshot().id());

        // Held and never approved: content that exists but was never served to anybody.
        addUpstreamCommit(upstream, "held");
        Snapshot held = ingestionService.ingest(ours.marketplace(), null);
        assertThat(held.state()).isEqualTo(Snapshot.HELD);

        String scoped =
                tokenService.create("alice", "scoped", List.of(mine), null).token();

        JsonNode answers = answers(
                scoped,
                List.of(
                        pair(mine, approved.sha()),
                        pair(theirs, theirApproved.sha()),
                        pair(uniqueName("absent"), theirApproved.sha()),
                        pair(mine, NEVER_HELD),
                        pair(mine, held.sha())));

        assertThat(state(answers, 0)).isEqualTo(HeldContentController.APPROVED);
        for (int i = 1; i < answers.size(); i++) {
            assertThat(state(answers, i))
                    .as("answer %d claims no approval", i)
                    .isEqualTo(HeldContentController.UNKNOWN);
        }
        // Not merely "all unknown": the out-of-scope marketplace and the one that does not exist
        // must be the same answer field for field, and so must the never-held commit and the one
        // held but never approved.
        assertThat(withoutIdentity(answers.get(1))).isEqualTo(withoutIdentity(answers.get(2)));
        assertThat(withoutIdentity(answers.get(3))).isEqualTo(withoutIdentity(answers.get(4)));

        // An unscoped token keeps its every-marketplace behaviour (GW_AUTH_0006).
        JsonNode unscoped = answers(newPat(), List.of(pair(theirs, theirApproved.sha())));
        assertThat(state(unscoped, 0)).isEqualTo(HeldContentController.APPROVED);
    }

    /**
     * No credential, no answer — and no session is honoured or created on this chain, which is the
     * half worth asserting: the check is a sibling of the facade, not a mode on the web surface, so
     * a browser-borne credential must not reach it (GW_FACADE_0032).
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0032"})
    void an_unauthenticated_caller_is_refused_and_no_session_is_honoured_or_created() throws Exception {
        mockMvc.perform(check(null, List.of(pair("anything", NEVER_HELD)))).andExpect(status().isUnauthorized());
        mockMvc.perform(check(null, List.of(pair("anything", NEVER_HELD))).cookie(new Cookie("JSESSIONID", "forged")))
                .andExpect(status().isUnauthorized());

        MockHttpServletResponse answered = mockMvc.perform(check(newPat(), List.of(pair("anything", NEVER_HELD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(answered.getHeaders(HttpHeaders.SET_COOKIE))
                .as("a successful check creates no session")
                .isEmpty();
    }

    /**
     * The reason is withheld (GW_FACADE_0032). It is mandatory on a revocation and routinely names
     * an undisclosed vulnerability or a compromised maintainer; an identity entitled to it reads it
     * from the ledger, which is an auditor's read.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0032"})
    void the_revocation_reason_never_reaches_the_answer() throws Exception {
        String pat = newPat();
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("reason");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());
        String secret = "CVE-2026-9999 in a transitive dependency";
        revocationService.revoke(approved.id(), secret, RevocationService.ServeAfter.NOTHING, "root");

        String body = body(pat, List.of(pair(name, approved.sha())));
        assertThat(body).doesNotContain("CVE-2026-9999").doesNotContain("transitive");
    }

    /**
     * A revocation is observable through the check by the next request (GW_FACADE_0031), and none
     * of these questions appends anything to the fetch ledger (design D6): a row per client per
     * poll would multiply the growth of the table the compliance story rests on, to record that
     * somebody asked a question rather than received content.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0031"})
    void the_check_reads_the_record_and_writes_nothing_to_the_ledger() throws Exception {
        String pat = newPat();
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("ledger");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());

        int before = fetchLogRepository.list().size();
        assertThat(state(answers(pat, List.of(pair(name, approved.sha()))), 0))
                .isEqualTo(HeldContentController.APPROVED);

        revocationService.revoke(approved.id(), "withdrawn", RevocationService.ServeAfter.NOTHING, "root");
        assertThat(state(answers(pat, List.of(pair(name, approved.sha()))), 0))
                .as("the check reads the record, not a cache")
                .isEqualTo(HeldContentController.REVOKED);

        // Counted rather than merely inspected: the revocation's own two entries are the whole
        // growth, so neither question left anything behind.
        assertThat(fetchLogRepository.list().stream()
                        .skip(before)
                        .map(entry -> String.valueOf(entry.get("event")))
                        .toList())
                .as("the revocation is recorded; neither question is")
                .containsExactly(RevocationService.EVENT_REVOKED, RevocationService.EVENT_UNPUBLISHED);
    }

    /**
     * Deny by default for the prefix (design D8). {@code RoleEnforcementTests} walks {@code /api/**}
     * and fails when a route appears there without a deliberate classification; {@code /status/**}
     * is outside that walk, so the protection does not extend here by itself. A route added under
     * this prefix fails this assertion rather than shipping on whatever the chain happens to allow.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0032"})
    void the_status_prefix_carries_exactly_one_route() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : webApplicationContext
                .getBeansOfType(RequestMappingHandlerMapping.class)
                .values()) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                RequestMethodsRequestCondition methods = info.getMethodsCondition();
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    if (!pattern.startsWith("/status/")) {
                        continue;
                    }
                    for (RequestMethod method : methods.getMethods()) {
                        routes.add(method.name() + " " + pattern);
                    }
                }
            }
        }
        assertThat(routes)
                .as("a route added under /status/** needs a deliberate entry here and its own authorization story")
                .containsExactly("POST /status/snapshots");
    }

    private JsonNode answers(String pat, List<String> pairs) throws Exception {
        return MAPPER.readTree(body(pat, pairs)).get("results");
    }

    private String body(String pat, List<String> pairs) throws Exception {
        return mockMvc.perform(check(pat, pairs))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static MockHttpServletRequestBuilder check(String pat, List<String> pairs) {
        MockHttpServletRequestBuilder request = post("/status/snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdings\":[" + String.join(",", pairs) + "]}");
        return pat == null ? request : request.header(HttpHeaders.AUTHORIZATION, basic(pat));
    }

    private static String pair(String marketplace, String sha) {
        return "{\"marketplace\":\"" + marketplace + "\",\"sha\":\"" + sha + "\"}";
    }

    private static String state(JsonNode answers, int index) {
        return answers.get(index).get("state").asText();
    }

    /** The answer with the pair it echoes removed, so two answers can be compared for what they disclose. */
    private static JsonNode withoutIdentity(JsonNode answer) {
        return ((com.fasterxml.jackson.databind.node.ObjectNode) answer.deepCopy())
                .remove(List.of("marketplace", "sha"));
    }

    private static String basic(String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(("token:" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
