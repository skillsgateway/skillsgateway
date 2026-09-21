package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * The seam between a chain change and the approval gate (GW_VETTING_0038, GW_VETTING_0038.1).
 *
 * <p>Enabling a vetter re-runs nothing. For approved content the sweep converges; for a snapshot
 * still at the gate nothing does, so it can be approved on evidence the newly enabled vetter never
 * produced. These tests pin the two halves of the answer this change takes: the gateway says so,
 * and it does not refuse.
 *
 * <p>The second half is the one worth guarding. "Marks it" is a decision that a later change could
 * quietly turn into a gate by adding one condition, and a test that only proved the marking exists
 * would not notice. So the publication is asserted through a real clone, not through a state
 * column.
 */
class ChainStalenessAtTheGateTests extends AbstractGatewayTest {

    private static final String EVENT = ApprovalService.EVENT_APPROVED_ON_SUPERSEDED_CHAIN;

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    private List<Map<String, Object>> stalenessEntries(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")) && EVENT.equals(entry.get("event")))
                .toList();
    }

    private void disable(String vetter, String marketplace) throws Exception {
        mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", vetter)
                        .with(root)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\", \"reason\": \"chain change\"}"
                                .formatted(marketplace)))
                .andExpect(status().isOk());
    }

    /**
     * The whole defect, end to end: content vetted yesterday, a chain changed today, and the
     * reviewer told — then approved anyway, because that is their call to make.
     */
    @Test
    @SVCs({"SVC_GW_VETTING_0038", "SVC_GW_VETTING_0038.1"})
    void a_chain_change_makes_held_evidence_superseded_and_the_approval_still_publishes() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("stale-gate"), createUpstream(DEFAULT_MANIFEST), "root", "root");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();

        // Ingested against the chain in force, so the evidence is current.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", id).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainStaleness.state").value("IN_FORCE"));

        disable("secret-scan", name);

        // The same stored run, now produced by a chain the marketplace no longer runs.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", id).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainStaleness.state").value("SUPERSEDED"))
                // A switched-off vetter stays named in the chain and is recorded as disabled evidence
                // (GW_VETTING_0029.2), so the difference is in what was switched off, not in who is listed.
                .andExpect(jsonPath("$.chainStaleness.runChain")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("disabled="))))
                .andExpect(jsonPath("$.chainStaleness.currentChain")
                        .value(org.hamcrest.Matchers.containsString("disabled=[secret-scan]")));

        mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id).with(root).with(csrf()))
                .andExpect(status().isOk());

        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        // Published for real. A state column would not catch a change that marked and then refused
        // to serve; a clone does.
        assertThat(gitClone(facadeUrl(name, newPat()), newWorkDir("staleclone").resolve("repo"))
                        .exitCode())
                .as("the marking is not a gate: stale evidence still publishes")
                .isZero();

        assertThat(stalenessEntries(name)).singleElement().satisfies(entry -> {
            assertThat(entry.get("principal")).isEqualTo("root");
            assertThat(entry.get("sha")).isEqualTo(registered.snapshot().sha());
            // Both chains, or the entry cannot answer "what did the reviewer not see?".
            assertThat(String.valueOf(entry.get("detail")))
                    .contains("evidenceChain=")
                    .contains("chainInForce=")
                    .contains("secret-scan");
        });
        // The decision entry is still there: this is written beside the approval, never instead.
        assertThat(fetchLogRepository.list())
                .filteredOn(entry ->
                        name.equals(entry.get("marketplace")) && "snapshot-approved".equals(entry.get("event")))
                .isNotEmpty();
    }

    /**
     * The other half, which is what makes the entry mean anything: an ordinary approval against
     * unchanged configuration writes no staleness row, so a row is evidence rather than noise.
     */
    @Test
    @SVCs({"SVC_GW_VETTING_0038.1"})
    void an_approval_on_evidence_from_the_chain_in_force_records_nothing() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fresh-gate"), createUpstream(DEFAULT_MANIFEST), "root", "root");
        String name = registered.marketplace().name();

        mockMvc.perform(post(
                                "/api/v1/snapshots/{id}/approve",
                                registered.snapshot().id())
                        .with(root)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(stalenessEntries(name)).isEmpty();
    }

    /**
     * The marking is only worth making if it can be acted on. Refreshing a held snapshot's evidence
     * is deliberately not re-vetting: there are no fetchers, nothing published to retract, and a
     * blocking verdict here is the approval gate working rather than a retroactive violation.
     */
    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void a_held_snapshots_evidence_is_refreshed_in_place_and_becomes_current_again() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("refresh"), createUpstream(DEFAULT_MANIFEST), "root", "root");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();

        disable("secret-scan", name);
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", id).with(root))
                .andExpect(jsonPath("$.chainStaleness.state").value("SUPERSEDED"));

        mockMvc.perform(post("/api/v1/snapshots/{id}/revet", id).with(root).with(csrf()))
                .andExpect(status().isOk())
                // Nothing was retracted, because nothing was published.
                .andExpect(jsonPath("$.revoked").value(false));

        // The new run was produced by the chain in force, so the evidence is current again.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", id).with(root))
                .andExpect(jsonPath("$.chainStaleness.state").value("IN_FORCE"));
        assertThat(snapshotRepository.findById(id).orElseThrow().state())
                .as("refreshing evidence decides nothing about the snapshot")
                .isEqualTo(Snapshot.HELD);
        // And it is not dressed up as a retraction: no violation row for content in the field.
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> name.equals(entry.get("marketplace"))
                        && String.valueOf(entry.get("event")).startsWith("revet-violation"))
                .as("a held snapshot has no fetchers and nothing to retract")
                .isEmpty();
    }

    /** Terminal states stay refused: this is for content still at the gate, nothing else. */
    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void a_rejected_snapshot_is_still_refused_a_refresh() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("refuse"), createUpstream(DEFAULT_MANIFEST), "root", "root");
        long id = registered.snapshot().id();

        mockMvc.perform(post("/api/v1/snapshots/{id}/reject", id).with(root).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/snapshots/{id}/revet", id).with(root).with(csrf()))
                .andExpect(status().isConflict());
    }
}
