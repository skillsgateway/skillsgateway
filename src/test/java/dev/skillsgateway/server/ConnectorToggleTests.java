package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.ConnectorToggleService;
import dev.skillsgateway.server.vetting.VettingChain;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * The administrative connector on/off switch (GW_0143). Adversarial: a non-administrator can
 * neither switch a connector nor even see the settings, a disabled connector is recorded on the run
 * as fail-loud evidence rather than silently vanishing, and disabling every connector leaves a run
 * blocked rather than clear so the switch can never be a blanket approval.
 */
class ConnectorToggleTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    @Test
    @SVCs({"SVC_GW_0143"})
    void an_admin_disables_a_connector_per_marketplace_and_no_one_else_can() throws Exception {
        String nameA = uniqueName("toggle-a");
        String nameB = uniqueName("toggle-b");
        Marketplace a = register(nameA, plantedUpstream());
        Marketplace b = register(nameB, plantedUpstream());

        // A non-administrator can neither switch a connector nor read the settings.
        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        mockMvc.perform(put("/api/vetting/connectors/{name}/toggle", "secret-scan")
                        .with(mallory)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\"}".formatted(nameA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vetting/connector-toggles").with(mallory)).andExpect(status().isForbidden());

        // An administrator disables secret-scan for marketplace A only.
        mockMvc.perform(put("/api/vetting/connectors/{name}/toggle", "secret-scan")
                        .with(root)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\", \"reason\": \"vendor keys, expected\"}"
                                .formatted(nameA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // The toggle is on the ledger, naming the administrator, the connector and the scope.
        assertThat(fetchLogRepository.list().stream()
                        .filter(e -> ConnectorToggleService.EVENT_DISABLED.equals(e.get("event")))
                        .filter(e -> nameA.equals(e.get("marketplace")))
                        .toList())
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.get("principal")).isEqualTo("root");
                    assertThat(String.valueOf(e.get("detail")))
                            .contains("secret-scan")
                            .contains("enabled=false");
                });

        // Ingesting A now records secret-scan as a disabled verdict and no longer blocks on it;
        // the other connectors provide the positive evidence, so the effective outcome clears.
        Snapshot onA = ingestionService.ingest(a, null);
        mockMvc.perform(get("/api/snapshots/{id}/vetting", onA.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.CLEAR.name()))
                .andExpect(jsonPath("$.run.verdicts[?(@.connector == 'secret-scan')].state")
                        .value("DISABLED"));

        // Marketplace B, left alone, still runs secret-scan and still blocks on the same content.
        Snapshot onB = ingestionService.ingest(b, null);
        mockMvc.perform(get("/api/snapshots/{id}/vetting", onB.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.name()));
    }

    @Test
    @SVCs({"SVC_GW_0143"})
    void disabling_every_connector_leaves_a_run_blocked_not_clear() throws Exception {
        // Scoped to one marketplace so the shared context is untouched: switching every connector
        // off for it is the integration-level counterpart of the pure-function "disable-all blocks"
        // case, exercised through the real chain rather than the aggregate function alone.
        String name = uniqueName("disable-all");
        Marketplace c = register(name, plantedUpstream());
        for (String connector : List.of("secret-scan", "prompt-injection", "license-scan")) {
            mockMvc.perform(put("/api/vetting/connectors/{name}/toggle", connector)
                            .with(root)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false, \"marketplace\": \"%s\"}".formatted(name)))
                    .andExpect(status().isOk());
        }
        Snapshot snapshot = ingestionService.ingest(c, null);
        // Nothing ran, so nothing cleared: a marketplace with every control switched off is blocked,
        // never cleared — the switch is not a blanket approval.
        mockMvc.perform(get("/api/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.name()));
    }

    private Marketplace register(String name, Path upstream) {
        return marketplaceRepository.register(
                name,
                upstream.toAbsolutePath().toString(),
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null);
    }

    private static Path plantedUpstream() throws Exception {
        return createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/hello/NOTES.md", PLANTED_SECRET));
    }
}
