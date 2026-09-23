package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The marketplace read names the commit its facade serves (GW_INGEST_0033) — as a fact read from
 * the served reference, not an inference from which snapshots are recorded approved.
 *
 * <p>The interesting case is the one where those two answers disagree, and it is reachable only
 * one way: approve a snapshot, approve a successor over it, then withdraw the successor choosing
 * to serve nothing. The predecessor keeps its approval — withdrawing one snapshot says nothing
 * about the others — while the facade serves none of them. "Newest approved" then names the
 * predecessor, confidently, and nobody can fetch it.
 *
 * <p>A simpler test — one snapshot, withdrawn — would pass without proving anything, because no
 * approved row would be left for an inference to be fooled by.
 */
class ServedCommitTests extends AbstractGatewayTest {

    @Autowired
    private RevocationService revocationService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode marketplace(String name) throws Exception {
        String body = mockMvc.perform(
                        get("/api/v1/marketplaces").with(oidcLogin().idToken(t -> t.subject("root"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (JsonNode node : MAPPER.readTree(body)) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("marketplace " + name + " not in the read");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0033"})
    void a_marketplace_that_has_never_published_serves_nothing() throws Exception {
        Registered registered = registerAndIngest(uniqueName("unpublished"), createUpstream(DEFAULT_MANIFEST));

        assertThat(marketplace(registered.marketplace().name()).get("servedSha").isNull())
                .as("held content is not served")
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0033"})
    void the_served_commit_is_the_approved_one_the_facade_answers_with() throws Exception {
        Registered registered = registerAndIngest(uniqueName("serving"), createUpstream(DEFAULT_MANIFEST));
        Snapshot approved = approve(registered.snapshot().id());

        assertThat(marketplace(registered.marketplace().name()).get("servedSha").asText())
                .isEqualTo(approved.sha());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0033"})
    void an_approved_record_left_behind_by_a_withdrawal_is_not_reported_as_served() throws Exception {
        String name = uniqueName("dark");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());

        addUpstreamCommit(upstream, "second revision");
        Snapshot second = ingestionService.ingest(first.marketplace(), "alice");
        approve(second.id());

        revocationService.revoke(
                second.id(), "the successor is poisoned", RevocationService.ServeAfter.NOTHING, "root");

        // The precondition the whole test rests on: an approved record really does remain.
        assertThat(snapshotRepository
                        .findById(first.snapshot().id())
                        .orElseThrow()
                        .state())
                .as("the predecessor keeps its approval — this is what an inference would be fooled by")
                .isEqualTo(Snapshot.APPROVED);

        assertThat(marketplace(name).get("servedSha").isNull())
                .as("the facade serves nothing, so nothing is reported served — not the approved predecessor")
                .isTrue();
    }
}
