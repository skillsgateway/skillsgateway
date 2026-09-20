package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.Vetter;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingChainSettingsService;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * The chain mode and the vetter order (GW_VETTING_0032, GW_VETTING_0033), attacked rather than
 * demonstrated: the shorter chain must never be the cheaper answer.
 *
 * <p>The adversarial core is {@link #waiving_the_stopping_finding_does_not_clear_a_short_circuited_run()}
 * — accepting the risk that stopped the chain must not open a gate on evidence the vetters after it
 * never gathered. Around it: a non-administrator can reach neither setting, an order naming a vetter
 * that does not exist is refused and not stored, and a refused change writes nothing to the ledger.
 *
 * <p>Two conventions keep this class from disturbing the shared context it runs in. Every setting
 * with a behavioural effect is scoped to a marketplace this class registered; the only settings
 * written at the global scope are deliberate no-ops — {@code run-all} is the default mode, and the
 * global order is the configured order — written solely so the resolution has a global source to
 * report. Only {@link #the_mode_and_the_order_resolve_per_marketplace_then_globally_then_by_default()}
 * writes at the global scope at all, so its default-resolution assertions cannot race another
 * method in this class.
 */
class VettingChainSettingsTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));
    private final OidcLoginRequestPostProcessor mallory = oidcLogin().idToken(token -> token.subject("mallory"));

    @Autowired
    private VettingService vettingService;

    @Autowired
    private VettingChainSettingsService chainSettings;

    @Autowired
    private WaiverService waiverService;

    @Test
    @SVCs({"SVC_GW_VETTING_0032", "SVC_GW_VETTING_0032.2"})
    void the_chain_stops_after_a_failure_and_records_what_it_never_reached() throws Exception {
        String name = uniqueName("stop-after-fail");
        Marketplace marketplace = register(name, plantedUpstream());
        List<String> chain = orderedNames(marketplace.id());
        assertThat(chain).hasSizeGreaterThanOrEqualTo(3);

        setMode("stop-after-fail", name, "expensive external review last");

        Snapshot snapshot = ingestionService.ingest(marketplace, null);

        // Every vetter of the chain still has a verdict: a shorter chain would be the failure this
        // state exists to prevent. The ones after secret-scan say they were not reached, and say
        // which vetter stopped the chain.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.verdicts.length()").value(chain.size()))
                .andExpect(jsonPath("$.run.verdicts[?(@.vetter == 'secret-scan')].state")
                        .value("fail"))
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.stored()));

        for (String vetter : chain.subList(chain.indexOf("secret-scan") + 1, chain.size())) {
            mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                    .andExpect(jsonPath("$.run.verdicts[?(@.vetter == '%s')].state".formatted(vetter))
                            .value("not_reached"))
                    .andExpect(jsonPath("$.run.verdicts[?(@.vetter == '%s')].detail".formatted(vetter))
                            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("secret-scan"))));
        }

        // The ledger says the same thing, per verdict, without a join into the vetting tables.
        assertThat(fetchLogRepository.list().stream()
                        .filter(e -> "vetting-verdict".equals(e.get("event")))
                        .filter(e -> name.equals(e.get("marketplace")))
                        .map(e -> String.valueOf(e.get("detail")))
                        .filter(detail -> detail.contains("not_reached"))
                        .toList())
                .isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).contains("secret-scan"));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.3"})
    void waiving_the_stopping_finding_does_not_clear_a_short_circuited_run() throws Exception {
        String name = uniqueName("stop-waive");
        Marketplace marketplace = register(name, plantedUpstream());
        setMode("stop-after-fail", name, null);
        Snapshot snapshot = ingestionService.ingest(marketplace, null);

        // Accept the risk the chain stopped on, exactly as a reviewer would.
        waiverService.create(
                snapshot.id(),
                RULE,
                WaiverScope.SNAPSHOT,
                null,
                "documented dummy key",
                Instant.now().plus(Duration.ofDays(7)),
                "alice");

        // The waiver is genuinely suppressing the finding — this is not a test of a waiver that
        // failed to match — and the run is blocked all the same, because the vetters after
        // secret-scan never looked at this content.
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressed.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.stored()));

        assertThatThrownBy(() -> approvalService.approve(snapshot.id(), "alice"))
                .isInstanceOf(VettingBlockedException.class);

        // The remedy is a fresh run, not a second waiver: with the finding suppressed the chain no
        // longer stops, every vetter looks, and only then is the gate decided.
        vettingService.run(snapshot, name, "revet-manual");
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.run.verdicts[?(@.state == 'NOT_REACHED')]").isEmpty())
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.CLEAR_WITH_WAIVERS.stored()));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.1", "SVC_GW_VETTING_0033"})
    void the_mode_and_the_order_resolve_per_marketplace_then_globally_then_by_default() throws Exception {
        String nameA = uniqueName("resolve-a");
        String nameB = uniqueName("resolve-b");
        Marketplace a = register(nameA, plantedUpstream());
        register(nameB, plantedUpstream());
        List<String> configured =
                vettingService.vetters().stream().map(Vetter::name).toList();

        // No setting anywhere: the default, and named as the default rather than as a decision.
        chainSettingsOf(nameA)
                .andExpect(jsonPath("$.mode").value("run-all"))
                .andExpect(jsonPath("$.modeSource").value("default"))
                .andExpect(jsonPath("$.orderSource").value("default"))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.equalTo(configured)));

        // A global setting that changes nothing about what runs — run-all is the default, and the
        // global order is the configured order — so it cannot leak into another test's
        // expectations while still being a setting whose source has to read GLOBAL.
        setMode("run-all", null, "the estate default, stated");
        setOrder(configured, null, "the configured order, stated");
        chainSettingsOf(nameA)
                .andExpect(jsonPath("$.modeSource").value("global"))
                .andExpect(jsonPath("$.orderSource").value("global"))
                .andExpect(jsonPath("$.modeReason").value("the estate default, stated"))
                .andExpect(jsonPath("$.modeUpdatedBy").value("root"));
        chainSettingsOf(nameB).andExpect(jsonPath("$.modeSource").value("global"));

        // A marketplace's own setting wins, and only for it.
        List<String> reordered = reversed(configured);
        setMode("stop-after-fail", nameA, "secret-scan is cheap, the rest are not");
        setOrder(reordered, nameA, "cheapest first");
        chainSettingsOf(nameA)
                .andExpect(jsonPath("$.mode").value("stop-after-fail"))
                .andExpect(jsonPath("$.modeSource").value("marketplace"))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.equalTo(reordered)))
                .andExpect(jsonPath("$.orderOverride").value(org.hamcrest.Matchers.equalTo(reordered)))
                .andExpect(jsonPath("$.orderSource").value("marketplace"));
        chainSettingsOf(nameB)
                .andExpect(jsonPath("$.mode").value("run-all"))
                .andExpect(jsonPath("$.modeSource").value("global"));

        // The resolution the chain itself applies, not a recombination of the settings list.
        assertThat(orderedNames(a.id())).isEqualTo(reordered);

        // And the effective chain read reports the vetters in that same resolved order.
        mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain", nameA).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value(reordered.getFirst()));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0033"})
    void an_order_decides_which_vetter_stops_the_chain() throws Exception {
        String name = uniqueName("ordered-stop");
        Marketplace marketplace = register(name, plantedUpstream());
        List<String> configured =
                vettingService.vetters().stream().map(Vetter::name).toList();
        assertThat(configured).contains("secret-scan", "prompt-injection");

        // secret-scan last: everything before it now runs, and it is where the chain stops.
        List<String> order = configured.stream()
                .filter(vetter -> !"secret-scan".equals(vetter))
                .toList();
        setOrder(order, name, "secret-scan last, deliberately");
        setMode("stop-after-fail", name, null);

        Snapshot snapshot = ingestionService.ingest(marketplace, null);
        assertThat(orderedNames(marketplace.id()).getLast()).isEqualTo("secret-scan");

        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", snapshot.id()).with(root))
                .andExpect(status().isOk())
                // The vetter an order does not name is not dropped: it runs after the named ones.
                .andExpect(jsonPath("$.run.verdicts.length()").value(configured.size()))
                // Nothing after it, so nothing was left unreached — the stop is at the end.
                .andExpect(
                        jsonPath("$.run.verdicts[?(@.state == 'NOT_REACHED')]").isEmpty())
                .andExpect(jsonPath("$.run.verdicts[?(@.vetter == 'secret-scan')].position")
                        .value(configured.size() - 1))
                .andExpect(jsonPath("$.outcome").value(VettingChain.Outcome.BLOCKED.stored()));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.4", "SVC_GW_VETTING_0033.1"})
    void neither_setting_is_reachable_by_a_non_administrator_and_a_refusal_records_nothing() throws Exception {
        String name = uniqueName("chain-refuse");
        register(name, plantedUpstream());
        int ledgerBefore = chainSettingEntries();

        // Not the switch, not the order, and not even the sight of either.
        mockMvc.perform(putJson("/api/v1/vetting/chain-mode", mallory, "{\"mode\": \"stop-after-fail\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(putJson("/api/v1/vetting/chain-order", mallory, "{\"vetters\": [\"secret-scan\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/vetting/chain-settings").with(mallory)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain-settings", name)
                        .with(mallory))
                .andExpect(status().isForbidden());

        // An order that names a vetter nothing carries, and one that names a vetter twice: refused
        // rather than stored, because an arrangement that matched nothing is a chain an
        // administrator believes they arranged.
        mockMvc.perform(putJson(
                        "/api/v1/vetting/chain-order",
                        root,
                        "{\"vetters\": [\"secret-scan\", \"no-such-vetter\"], \"marketplace\": \"%s\"}"
                                .formatted(name)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(putJson(
                        "/api/v1/vetting/chain-order",
                        root,
                        "{\"vetters\": [\"secret-scan\", \"secret-scan\"], \"marketplace\": \"%s\"}".formatted(name)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(putJson(
                        "/api/v1/vetting/chain-order",
                        root,
                        "{\"vetters\": [], \"marketplace\": \"%s\"}".formatted(name)))
                .andExpect(status().isUnprocessableEntity());

        // An unknown mode, and an omitted one.
        mockMvc.perform(putJson("/api/v1/vetting/chain-mode", root, "{\"mode\": \"stop-eventually\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(putJson("/api/v1/vetting/chain-mode", root, "{}")).andExpect(status().isUnprocessableEntity());

        // An unknown marketplace, for both.
        mockMvc.perform(putJson(
                        "/api/v1/vetting/chain-mode",
                        root,
                        "{\"mode\": \"stop-after-fail\", \"marketplace\": \"no-such-marketplace\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(putJson(
                        "/api/v1/vetting/chain-order",
                        root,
                        "{\"vetters\": [\"secret-scan\"], \"marketplace\": \"no-such-marketplace\"}"))
                .andExpect(status().isNotFound());

        // Nothing was stored and nothing was said on the ledger for any of it.
        assertThat(chainSettingEntries()).isEqualTo(ledgerBefore);
        // Nothing was stored for this marketplace: whatever the global setting happens to be, the
        // refused orders did not become a setting of its own.
        chainSettingsOf(name).andExpect(jsonPath("$.orderSource").value(org.hamcrest.Matchers.not("marketplace")));

        // The accepted change, by contrast, is on the ledger naming the identity, the scope and the
        // new value.
        setMode("stop-after-fail", name, "expensive external review last");
        assertThat(fetchLogRepository.list().stream()
                        .filter(e -> VettingChainSettingsService.EVENT_MODE_SET.equals(e.get("event")))
                        .filter(e -> name.equals(e.get("marketplace")))
                        .toList())
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.get("principal")).isEqualTo("root");
                    assertThat(String.valueOf(e.get("detail")))
                            .contains("mode=stop-after-fail")
                            .contains("marketplace(" + name + ")")
                            .contains("expensive external review last");
                });

        mockMvc.perform(get("/api/v1/vetting/chain-settings").with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modes").isArray())
                .andExpect(jsonPath("$.orders").isArray());
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0033.2"})
    void a_run_records_the_mode_and_the_order_it_used() throws Exception {
        String name = uniqueName("chain-identity");
        Marketplace marketplace = register(name, plantedUpstream());
        Snapshot snapshot = ingestionService.ingest(marketplace, null);
        String first = chainOfLatestRun(snapshot.id());
        assertThat(first).endsWith(";mode=run-all");

        setMode("stop-after-fail", name, null);
        vettingService.run(snapshot, name, "revet-manual");
        String afterMode = chainOfLatestRun(snapshot.id());
        assertThat(afterMode).endsWith(";mode=stop-after-fail").isNotEqualTo(first);

        setOrder(reversed(orderedNames(marketplace.id())), name, null);
        vettingService.run(snapshot, name, "revet-manual");
        String afterOrder = chainOfLatestRun(snapshot.id());
        assertThat(afterOrder).isNotEqualTo(afterMode).isNotEqualTo(first);
        // The vetters, in the order they ran, are what changed — the mode did not.
        assertThat(afterOrder).endsWith(";mode=stop-after-fail");
    }

    private String chainOfLatestRun(long snapshotId) {
        return vettingService.latestRun(snapshotId).orElseThrow().chain();
    }

    private List<String> orderedNames(long marketplaceId) {
        return chainSettings.orderedVetters(marketplaceId).stream()
                .map(Vetter::name)
                .toList();
    }

    private static List<String> reversed(List<String> names) {
        return names.reversed();
    }

    private int chainSettingEntries() {
        return (int) fetchLogRepository.list().stream()
                .filter(e -> VettingChainSettingsService.EVENT_MODE_SET.equals(e.get("event"))
                        || VettingChainSettingsService.EVENT_ORDER_SET.equals(e.get("event")))
                .count();
    }

    private org.springframework.test.web.servlet.ResultActions chainSettingsOf(String marketplace) throws Exception {
        return mockMvc.perform(get("/api/v1/marketplaces/{name}/vetting-chain-settings", marketplace)
                        .with(root))
                .andExpect(status().isOk());
    }

    private void setMode(String mode, String marketplace, String reason) throws Exception {
        mockMvc.perform(putJson("/api/v1/vetting/chain-mode", root, json("mode", quote(mode), marketplace, reason)))
                .andExpect(status().isOk());
    }

    private void setOrder(List<String> vetters, String marketplace, String reason) throws Exception {
        String array =
                vetters.stream().map(VettingChainSettingsTests::quote).toList().toString();
        mockMvc.perform(putJson("/api/v1/vetting/chain-order", root, json("vetters", array, marketplace, reason)))
                .andExpect(status().isOk());
    }

    private static String json(String field, String value, String marketplace, String reason) {
        StringBuilder body = new StringBuilder("{\"%s\": %s".formatted(field, value));
        if (marketplace != null) {
            body.append(", \"marketplace\": ").append(quote(marketplace));
        }
        if (reason != null) {
            body.append(", \"reason\": ").append(quote(reason));
        }
        return body.append('}').toString();
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            String path, OidcLoginRequestPostProcessor principal, String body) {
        return put(path).with(principal).contentType(MediaType.APPLICATION_JSON).content(body);
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
