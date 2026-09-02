package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The administrative override of a blocked vetting outcome (GW_0148) — the cockpit model's "captain
 * disconnects the autopilot". Adversarial where it counts: a marketplace approver who may approve a
 * clean snapshot must not be able to override a blocked one, an override with no reason is refused,
 * and every accepted override leaves a distinct, unmistakable trail.
 */
class VettingOverrideTests extends AbstractGatewayTest {

    /** A shaped AWS access key id — belongs to nobody; secret-scan fails on its shape. */
    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    @Test
    @SVCs({"SVC_GW_0148"})
    void an_admin_overrides_a_blocked_outcome_with_a_reason_and_no_one_else_can() throws Exception {
        String name = uniqueName("override");
        Registered fixture = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/hello/NOTES.md", PLANTED_SECRET)));
        long snapshotId = fixture.snapshot().id();
        // The snapshot is blocked: secret-scan failed on the planted key, no waiver covers it.
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);

        // An approver of this very marketplace may approve a clean snapshot, but not override a
        // blocked one: a plain approval is refused for the block (409), and the override is refused
        // because it is not their act to make (403).
        String bobName = "bob-" + uniqueName("p");
        var bob = oidcLogin().idToken(token -> token.subject(bobName));
        grantApprover(bobName, name);
        mockMvc.perform(post("/api/snapshots/{id}/approve", snapshotId).with(bob))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/snapshots/{id}/approve", snapshotId)
                        .with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideVetting\": true, \"reason\": \"I accept the risk\"}"))
                .andExpect(status().isForbidden());
        // Nothing moved: the snapshot is still held and no override was recorded.
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);
        assertThat(approvalService.vettingOverride(snapshotId)).isEmpty();

        // An administrator may override — but only with a reason. Reasonless is refused (422).
        mockMvc.perform(post("/api/snapshots/{id}/approve", snapshotId)
                        .with(root)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideVetting\": true}"))
                .andExpect(status().isUnprocessableEntity());
        // A plain administrative approval of the blocked snapshot is still refused: the override is
        // the only way past the block, and it is deliberate.
        mockMvc.perform(post("/api/snapshots/{id}/approve", snapshotId).with(root))
                .andExpect(status().isConflict());
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);

        // The override with a reason approves and publishes the snapshot.
        String reason = "vendor-signed key, accepted risk tracked in TICKET-42";
        mockMvc.perform(post("/api/snapshots/{id}/approve", snapshotId)
                        .with(root)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideVetting\": true, \"reason\": \"%s\"}".formatted(reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(Snapshot.APPROVED));

        // The override left a distinct, unmistakable trail: its own ledger event, naming the
        // administrator, the reason and the connector that was blocking.
        List<Map<String, Object>> overrideEntries = fetchLogRepository.list().stream()
                .filter(entry -> ApprovalService.EVENT_OVERRIDE.equals(entry.get("event")))
                .filter(entry -> name.equals(entry.get("marketplace")))
                .toList();
        assertThat(overrideEntries).hasSize(1).first().satisfies(entry -> {
            assertThat(entry.get("principal")).isEqualTo("root");
            assertThat(String.valueOf(entry.get("detail"))).contains(reason).contains("secret-scan");
        });

        // Fail-loud: the vetting surface reports the override so it is never indistinguishable from
        // an approval the chain cleared on its own merits.
        mockMvc.perform(get("/api/snapshots/{id}/vetting", snapshotId).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.reason").value(reason))
                .andExpect(jsonPath("$.override.overriddenBy").value("root"));
    }

    private void grantApprover(String principal, String marketplace) throws Exception {
        mockMvc.perform(post("/api/roles")
                        .with(root)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal\": \"%s\", \"role\": \"approver\", \"marketplace\": \"%s\"}"
                                .formatted(principal, marketplace)))
                .andExpect(status().isCreated());
    }
}
