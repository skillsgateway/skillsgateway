package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The default mode (GW_0097): a conflict is recorded and the approval proceeds.
 *
 * <p>These tests exist because the default is a decision, not an absence of one. A deployment with
 * a single administrator — a first evaluation, a small team — has nobody to be the second pair of
 * eyes, so enforcement on by default would make the gateway unapprovable the day it is upgraded.
 * What replaces enforcement there is visibility: every self-approval lands on the ledger, so the
 * gap is measurable before anyone decides to close it. That is the whole content of warn mode, and
 * it is asserted here rather than assumed.
 *
 * <p>No {@code TestPropertySource}: the point of the class is that this is what an operator gets
 * without configuring anything.
 */
class FourEyesTests extends AbstractGatewayTest {

    @Autowired
    private FourEyesGate fourEyesGate;

    private List<Map<String, Object>> conflictEntries(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace"))
                        && FourEyesGate.EVENT_CONFLICT.equals(entry.get("event")))
                .toList();
    }

    /** The floor of the control: recording, never off. */
    @Test
    @SVCs({"SVC_GW_0097"})
    void theDefaultModeIsWarnAndThereIsNoWayToTurnDetectionOff() {
        assertThat(fourEyesGate.mode()).isEqualTo(SkillsGatewayProperties.FourEyesMode.WARN);
        assertThat(fourEyesGate.enforcing()).isFalse();
        // The enum is the whole vocabulary an operator has; an 'off' value would be visible here.
        assertThat(SkillsGatewayProperties.FourEyesMode.values())
                .containsExactly(
                        SkillsGatewayProperties.FourEyesMode.WARN, SkillsGatewayProperties.FourEyesMode.ENFORCE);
    }

    /**
     * The single-administrator case end to end, through the endpoint a reviewer actually uses: the
     * one person who registered the marketplace and pulled the content approves it, the content is
     * served, and the ledger says plainly that nobody independent looked at it.
     */
    @Test
    @SVCs({"SVC_GW_0097"})
    void aConflictedApprovalProceedsAndTheConflictIsRecordedBesideIt() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fe4warn"), createUpstream(DEFAULT_MANIFEST), "solo", "solo");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();

        mockMvc.perform(get("/api/snapshots/{id}/four-eyes", id)
                        .with(oidcLogin().idToken(token -> token.subject("solo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("WARN"))
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.conflicts.length()").value(2));

        mockMvc.perform(post("/api/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("solo")))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(gitClone(
                                facadeUrl(name, newPat()),
                                newWorkDir("fe4warnclone").resolve("repo"))
                        .exitCode())
                .as("warn mode publishes; only the record differs")
                .isZero();

        assertThat(conflictEntries(name)).singleElement().satisfies(entry -> {
            assertThat(entry.get("principal")).isEqualTo("solo");
            assertThat(entry.get("sha")).isEqualTo(registered.snapshot().sha());
            assertThat(String.valueOf(entry.get("detail")))
                    .contains("mode=WARN")
                    .contains("approved")
                    .contains(FourEyesGate.ROLE_INGESTED_BY)
                    .contains(FourEyesGate.ROLE_REGISTERED_BY);
        });
        // The approval itself is still recorded: the conflict entry is written beside the decision,
        // never instead of it, or the ledger would lose the approval it is commenting on.
        assertThat(fetchLogRepository.list())
                .filteredOn(entry ->
                        name.equals(entry.get("marketplace")) && "snapshot-approved".equals(entry.get("event")))
                .isNotEmpty();
    }

    /**
     * The other half of the claim, and the one that makes the ledger entry mean anything: an
     * ordinary independent review writes no conflict row, so a conflict row is evidence of a
     * self-approval rather than noise every approval produces.
     */
    @Test
    @SVCs({"SVC_GW_0097"})
    void anIndependentApprovalRecordsNoConflictAtAll() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fe4indep"), createUpstream(DEFAULT_MANIFEST), "solo", "solo");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();

        mockMvc.perform(get("/api/snapshots/{id}/four-eyes", id)
                        .with(oidcLogin().idToken(token -> token.subject("rachel"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts").isEmpty());
        mockMvc.perform(post("/api/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("rachel")))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(snapshotRepository.findById(id).orElseThrow().decidedBy()).isEqualTo("rachel");
        assertThat(conflictEntries(name)).isEmpty();
    }

    /**
     * The supply-side identities the rule reads are captured by the ordinary API path, not only by
     * a test helper: registering and ingesting over HTTP records the acting principal on the
     * marketplace and on the snapshot.
     */
    @Test
    @SVCs({"SVC_GW_0097"})
    void registrationAndIngestionOverHttpRecordTheActingIdentity() throws Exception {
        String name = uniqueName("fe4actor");
        String url = "file://" + createUpstream(DEFAULT_MANIFEST).toAbsolutePath();

        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin().idToken(token -> token.subject("dana")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registeredBy").value("dana"));

        mockMvc.perform(post("/api/marketplaces/{name}/ingest", name)
                        .with(oidcLogin().idToken(token -> token.subject("ingrid")))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ingestedBy").value("ingrid"));

        mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='%s')].registeredBy".formatted(name))
                        .value("dana"));
    }
}
