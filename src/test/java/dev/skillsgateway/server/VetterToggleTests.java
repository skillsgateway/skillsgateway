package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.Vetter;
import dev.skillsgateway.server.vetting.VetterToggleService;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * The administrative vetter on/off switch (GW_VETTING_0029). Adversarial: a non-administrator can
 * neither switch a vetter nor even see the settings, a disabled vetter is recorded on the run
 * as fail-loud evidence rather than silently vanishing, and disabling every vetter leaves a run
 * blocked rather than clear so the switch can never be a blanket approval.
 */
class VetterToggleTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    @Autowired
    private VettingService vettingService;

    @Test
    @SVCs({"SVC_GW_VETTING_0029.1", "SVC_GW_VETTING_0029.2", "SVC_GW_VETTING_0029.4"})
    void an_admin_disables_a_vetter_per_marketplace_and_no_one_else_can() throws Exception {
        String nameA = uniqueName("toggle-a");
        String nameB = uniqueName("toggle-b");
        Marketplace a = register(nameA, plantedUpstream());
        Marketplace b = register(nameB, plantedUpstream());

        // A non-administrator can neither switch a vetter nor read the settings.
        var mallory = oidcLogin().idToken(token -> token.subject("mallory"));
        mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", "secret-scan")
                        .with(mallory)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\"}".formatted(nameA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/vetting/vetter-toggles").with(mallory)).andExpect(status().isForbidden());

        // An administrator disables secret-scan for marketplace A only.
        mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", "secret-scan")
                        .with(root)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\", \"reason\": \"vendor keys, expected\"}"
                                .formatted(nameA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // The toggle is on the ledger, naming the administrator, the vetter and the scope.
        assertThat(fetchLogRepository.list().stream()
                        .filter(e -> VetterToggleService.EVENT_DISABLED.equals(e.get("event")))
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
        // the other vetters provide the positive evidence, so the effective outcome clears.
        Snapshot onA = ingestionService.ingest(a, null);
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", onA.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.CLEAR.stored()))
                .andExpect(jsonPath("$.run.verdicts[?(@.vetter == 'secret-scan')].state")
                        .value("disabled"));

        // Marketplace B, left alone, still runs secret-scan and still blocks on the same content.
        Snapshot onB = ingestionService.ingest(b, null);
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", onB.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.stored()));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0029.3"})
    void disabling_every_vetter_leaves_a_run_blocked_not_clear() throws Exception {
        // Scoped to one marketplace so the shared context is untouched: switching every vetter
        // off for it is the integration-level counterpart of the pure-function "disable-all blocks"
        // case, exercised through the real chain rather than the aggregate function alone.
        String name = uniqueName("disable-all");
        Marketplace c = register(name, plantedUpstream());
        // The real chain, not a list to keep in step: "every vetter" has to stay literally true
        // as vetters are added, or this test quietly stops testing what it names.
        for (String vetter : vettingService.vetters().stream().map(Vetter::name).toList()) {
            mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", vetter)
                            .with(root)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false, \"marketplace\": \"%s\"}".formatted(name)))
                    .andExpect(status().isOk());
        }
        Snapshot snapshot = ingestionService.ingest(c, null);
        // Nothing ran, so nothing cleared: a marketplace with every control switched off is blocked,
        // never cleared — the switch is not a blanket approval.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.stored()));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0029.5"})
    void the_effective_chain_names_each_vetters_state_and_the_setting_that_decided_it() throws Exception {
        String name = uniqueName("chain-view");
        register(name, plantedUpstream());
        List<String> chain = vettingService.vetters().stream().map(Vetter::name).toList();
        assertThat(chain).hasSizeGreaterThanOrEqualTo(3);
        String global = chain.get(0);
        String scoped = chain.get(1);
        String untouched = chain.get(2);

        // The global setting is deliberately enabled=true: it changes nothing about what runs, so
        // it cannot leak into another test's expectations, while still being a *setting* whose
        // source the chain has to report as global rather than as the default.
        toggle(global, null, true, "kept on across the estate");
        toggle(scoped, name, false, "vendor keys, expected");

        // Vetter settings are administrator-only to read (GW_VETTING_0029.4), and so is the chain
        // that reports them.
        mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain", name)
                        .with(oidcLogin().idToken(token -> token.subject("mallory"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain", name).with(root))
                .andExpect(status().isOk())
                // Every configured vetter, in the order the chain runs them.
                .andExpect(jsonPath("$.length()").value(chain.size()))
                .andExpect(jsonPath("$[0].name").value(global))
                .andExpect(jsonPath("$[1].name").value(scoped))
                .andExpect(jsonPath("$[2].name").value(untouched))
                // A setting at the global scope: enabled, and named as global rather than default.
                .andExpect(jsonPath("$[?(@.name == '%s')].source".formatted(global))
                        .value("global"))
                .andExpect(jsonPath("$[?(@.name == '%s')].enabled".formatted(global))
                        .value(true))
                .andExpect(jsonPath("$[?(@.name == '%s')].updatedBy".formatted(global))
                        .value("root"))
                // The per-marketplace setting wins, and carries its note.
                .andExpect(jsonPath("$[?(@.name == '%s')].source".formatted(scoped))
                        .value("marketplace"))
                .andExpect(jsonPath("$[?(@.name == '%s')].enabled".formatted(scoped))
                        .value(false))
                .andExpect(jsonPath("$[?(@.name == '%s')].reason".formatted(scoped))
                        .value("vendor keys, expected"))
                // No setting at all is its own source, not a missing value.
                .andExpect(jsonPath("$[?(@.name == '%s')].source".formatted(untouched))
                        .value("default"))
                .andExpect(jsonPath("$[?(@.name == '%s')].enabled".formatted(untouched))
                        .value(true))
                .andExpect(jsonPath("$[?(@.name == '%s')].version".formatted(untouched))
                        .isNotEmpty());

        mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain", "no-such-marketplace")
                        .with(root))
                .andExpect(status().isNotFound());
    }

    private void toggle(String vetter, String marketplace, boolean enabled, String reason) throws Exception {
        String body = marketplace == null
                ? "{\"enabled\": %s, \"reason\": \"%s\"}".formatted(enabled, reason)
                : "{\"enabled\": %s, \"marketplace\": \"%s\", \"reason\": \"%s\"}"
                        .formatted(enabled, marketplace, reason);
        mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", vetter)
                        .with(root)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
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
