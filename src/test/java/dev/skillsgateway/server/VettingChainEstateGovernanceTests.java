package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.vetting.VetterToggleService;
import dev.skillsgateway.server.vetting.VettingChainSettingsService;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The estate-wide half of the three chain settings: the reads that answer "what does a marketplace
 * with no override run" (GW_VETTING_0035), the removal of an override (GW_VETTING_0036), and one
 * change addressed to several marketplaces (GW_VETTING_0037).
 *
 * <p>The adversarial core is
 * {@link #writing_the_default_value_is_not_the_same_as_clearing_the_override()} — the whole reason
 * this change could not be faked in the browser. An override that happens to equal the default still
 * pins the marketplace, so a later global decision passes it by; only a removal puts the source back.
 * Around it: a non-administrator reaches none of it, a bulk request whose shape is wrong changes
 * nothing anywhere, one that names a marketplace that is gone still applies to the rest and says so,
 * and the ledger ties the entries of one act together.
 *
 * <p>Every setting written here is scoped to a marketplace this class registered. Nothing is written
 * at the global scope: a global row would change what every other marketplace in the shared context
 * resolves to, and the claims that matter here are about the global read being untouched by a
 * marketplace override, which needs no global write to make.
 */
class VettingChainEstateGovernanceTests extends AbstractGatewayTest {

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));
    private final OidcLoginRequestPostProcessor mallory = oidcLogin().idToken(token -> token.subject("mallory"));

    @Autowired
    private VettingService vettingService;

    @Test
    @SVCs({"SVC_GW_VETTING_0035"})
    void the_default_chain_is_read_from_the_gateway_and_never_reports_a_marketplace_source() throws Exception {
        String name = uniqueName("estate-default");
        Marketplace marketplace = register(name);
        String vetter = firstVetter();

        // A marketplace override is exactly what must not leak into the estate-wide answer.
        setMode("stop-after-fail", name);
        toggle(vetter, name, false);

        mockMvc.perform(get("/api/vetting/global-chain-settings").with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))))
                .andExpect(jsonPath("$.orderSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))))
                // The order is the whole chain, whatever any marketplace arranged.
                .andExpect(jsonPath("$.order.length()")
                        .value(vettingService.vetters().size()));

        mockMvc.perform(get("/api/vetting/global-chain").with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(vettingService.vetters().size()))
                .andExpect(jsonPath("$[?(@.source == 'MARKETPLACE')]").isEmpty())
                // The vetter this marketplace switched off still runs for everyone else.
                .andExpect(jsonPath("$[?(@.name == '%s')].enabled".formatted(vetter))
                        .value(true));

        // Meanwhile the marketplace's own read still says what it says: the two levels are separate.
        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", marketplace.name())
                        .with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeSource").value("MARKETPLACE"));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0035"})
    void the_estate_reads_are_refused_to_a_session_without_the_administrative_role() throws Exception {
        mockMvc.perform(get("/api/vetting/global-chain").with(mallory)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vetting/global-chain-settings").with(mallory)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0036"})
    void writing_the_default_value_is_not_the_same_as_clearing_the_override() throws Exception {
        String name = uniqueName("estate-clear-vs-default");
        register(name);

        // run-all is the default mode. Written as a marketplace setting it is still an override:
        // the marketplace is pinned, and the next global decision would pass it by.
        setMode("run-all", name);
        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", name)
                        .with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("run-all"))
                .andExpect(jsonPath("$.modeSource").value("MARKETPLACE"));

        assertThat(clear(name, List.of("mode")).applied()).isEqualTo(1);

        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", name)
                        .with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("run-all"))
                .andExpect(jsonPath("$.modeSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0036"})
    void clearing_removes_every_kind_of_override_and_is_recorded() throws Exception {
        String name = uniqueName("estate-clear-all");
        register(name);
        String vetter = firstVetter();

        setMode("stop-after-fail", name);
        setOrder(reversedChain(), name);
        toggle(vetter, name, false);

        int before = clearEntries(name);
        Counts counts = clear(name, List.of("mode", "order", "vetters"));
        assertThat(counts.applied()).isEqualTo(1);
        // One entry per kind removed: mode, order, and the one vetter override.
        assertThat(clearEntries(name) - before).isEqualTo(3);

        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", name)
                        .with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))))
                .andExpect(jsonPath("$.orderSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))))
                .andExpect(jsonPath("$.orderOverride.length()").value(0));
        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain", name).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'MARKETPLACE')]").isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0036"})
    void clearing_an_override_that_is_not_there_stores_nothing_and_records_nothing() throws Exception {
        String name = uniqueName("estate-clear-noop");
        register(name);

        int before = clearEntries(name);
        Counts counts = clear(name, List.of("mode", "order", "vetters"));

        assertThat(counts.applied()).isZero();
        assertThat(counts.unchanged()).isEqualTo(1);
        assertThat(clearEntries(name)).isEqualTo(before);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0037"})
    void a_bulk_change_whose_shape_is_wrong_is_refused_whole_and_changes_nothing() throws Exception {
        String name = uniqueName("estate-bulk-refused");
        register(name);
        int before = allChainEntries(name);

        // An unknown vetter, an unknown mode, an unknown action and an empty selection are all
        // knowable without touching a marketplace, so none of them may reach one.
        bulk(body(name, "set-vetter", "\"vetter\": \"does-not-exist\", \"enabled\": false"))
                .andExpect(status().isUnprocessableEntity());
        bulk(body(name, "set-mode", "\"mode\": \"whenever\"")).andExpect(status().isUnprocessableEntity());
        bulk(body(name, "burn-it-down", null)).andExpect(status().isUnprocessableEntity());
        bulk("{\"marketplaces\": [], \"action\": \"set-mode\", \"mode\": \"run-all\"}")
                .andExpect(status().isUnprocessableEntity());
        bulk(body(name, "clear", "\"clear\": []")).andExpect(status().isUnprocessableEntity());

        assertThat(allChainEntries(name)).isEqualTo(before);
        mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", name)
                        .with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeSource").value(org.hamcrest.Matchers.in(List.of("GLOBAL", "DEFAULT"))));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0037"})
    void a_marketplace_that_is_not_there_fails_alone_and_the_answer_says_so() throws Exception {
        String first = uniqueName("estate-bulk-a");
        String second = uniqueName("estate-bulk-b");
        register(first);
        register(second);
        String missing = uniqueName("estate-bulk-gone");

        MvcResult result = mockMvc.perform(
                        postJson("/api/vetting/chain-settings/bulk", root, """
                        {"marketplaces": ["%s", "%s", "%s"], "action": "set-mode",
                         "mode": "stop-after-fail", "reason": "estate-wide rollout"}""".formatted(first, missing, second)))
                // Not 200: a request that did not wholly happen must not answer as one that did.
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[?(@.marketplace == '%s')].status".formatted(missing))
                        .value("FAILED"))
                .andReturn();

        String correlationId = correlationId(result);

        // The two that exist did change, and each carries its own entry naming the shared reason and
        // the one id: an auditor reads them as one act without a summary row that could disagree.
        for (String name : List.of(first, second)) {
            mockMvc.perform(get("/api/marketplaces/{name}/vetting-chain-settings", name)
                            .with(root))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("stop-after-fail"))
                    .andExpect(jsonPath("$.modeSource").value("MARKETPLACE"));
        }
        List<String> details = detailsOf(correlationId);
        assertThat(details).hasSize(2);
        assertThat(details).allSatisfy(detail -> assertThat(detail).contains("reason=estate-wide rollout"));
        assertThat(marketplacesOf(correlationId)).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0037"})
    void a_bulk_change_is_refused_to_a_session_without_the_administrative_role() throws Exception {
        String name = uniqueName("estate-bulk-mallory");
        register(name);
        int before = allChainEntries(name);

        mockMvc.perform(postJson(
                        "/api/vetting/chain-settings/bulk",
                        mallory,
                        body(name, "set-mode", "\"mode\": \"stop-after-fail\"")))
                .andExpect(status().isForbidden());

        assertThat(allChainEntries(name)).isEqualTo(before);
    }

    // --- helpers -----------------------------------------------------------------------------

    private record Counts(int applied, int unchanged, int failed) {}

    private String firstVetter() {
        return vettingService.vetters().getFirst().name();
    }

    private List<String> reversedChain() {
        return vettingService.vetters().stream()
                .map(dev.skillsgateway.server.vetting.Vetter::name)
                .toList()
                .reversed();
    }

    private Counts clear(String marketplace, List<String> kinds) throws Exception {
        String array = kinds.stream()
                .map(VettingChainEstateGovernanceTests::quote)
                .toList()
                .toString();
        MvcResult result = mockMvc.perform(postJson(
                        "/api/vetting/chain-settings/bulk",
                        root,
                        body(marketplace, "clear", "\"clear\": " + array + ", \"reason\": \"back to the default\"")))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return new Counts(intField(json, "applied"), intField(json, "unchanged"), intField(json, "failed"));
    }

    private org.springframework.test.web.servlet.ResultActions bulk(String body) throws Exception {
        return mockMvc.perform(postJson("/api/vetting/chain-settings/bulk", root, body));
    }

    private static String body(String marketplace, String action, String extra) {
        return "{\"marketplaces\": [%s], \"action\": %s%s}"
                .formatted(quote(marketplace), quote(action), extra == null ? "" : ", " + extra);
    }

    private void setMode(String mode, String marketplace) throws Exception {
        mockMvc.perform(putJson(
                        "/api/vetting/chain-mode",
                        root,
                        "{\"mode\": %s, \"marketplace\": %s}".formatted(quote(mode), quote(marketplace))))
                .andExpect(status().isOk());
    }

    private void setOrder(List<String> vetters, String marketplace) throws Exception {
        String array = vetters.stream()
                .map(VettingChainEstateGovernanceTests::quote)
                .toList()
                .toString();
        mockMvc.perform(putJson(
                        "/api/vetting/chain-order",
                        root,
                        "{\"vetters\": %s, \"marketplace\": %s}".formatted(array, quote(marketplace))))
                .andExpect(status().isOk());
    }

    private void toggle(String vetter, String marketplace, boolean enabled) throws Exception {
        mockMvc.perform(putJson(
                        "/api/vetting/vetters/{name}/toggle".replace("{name}", vetter),
                        root,
                        "{\"enabled\": %s, \"marketplace\": %s}".formatted(enabled, quote(marketplace))))
                .andExpect(status().isOk());
    }

    private static final Set<String> CLEAR_EVENTS = Set.of(
            VetterToggleService.EVENT_CLEARED,
            VettingChainSettingsService.EVENT_MODE_CLEARED,
            VettingChainSettingsService.EVENT_ORDER_CLEARED);

    private static final Set<String> CHAIN_EVENTS = Set.of(
            VetterToggleService.EVENT_CLEARED,
            VetterToggleService.EVENT_ENABLED,
            VetterToggleService.EVENT_DISABLED,
            VettingChainSettingsService.EVENT_MODE_SET,
            VettingChainSettingsService.EVENT_MODE_CLEARED,
            VettingChainSettingsService.EVENT_ORDER_SET,
            VettingChainSettingsService.EVENT_ORDER_CLEARED);

    /**
     * Ledger counts are scoped to the marketplace this test registered. The ledger is one table
     * shared by every suite in the context, so an unscoped count is a race rather than an assertion.
     */
    private int clearEntries(String marketplace) {
        return entries(marketplace, CLEAR_EVENTS);
    }

    private int allChainEntries(String marketplace) {
        return entries(marketplace, CHAIN_EVENTS);
    }

    private int entries(String marketplace, Set<String> events) {
        return (int) fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")))
                .filter(entry -> events.contains(String.valueOf(entry.get("event"))))
                .count();
    }

    private List<String> detailsOf(String correlationId) {
        return fetchLogRepository.list().stream()
                .map(entry -> String.valueOf(entry.get("detail")))
                .filter(detail -> detail.contains("bulk=" + correlationId))
                .toList();
    }

    private List<String> marketplacesOf(String correlationId) {
        return fetchLogRepository.list().stream()
                .filter(entry -> String.valueOf(entry.get("detail")).contains("bulk=" + correlationId))
                .map(entry -> String.valueOf(entry.get("marketplace")))
                .toList();
    }

    private static String correlationId(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        int start = json.indexOf("\"correlationId\":\"") + "\"correlationId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static int intField(String json, String field) {
        String marker = "\"%s\":".formatted(field);
        int start = json.indexOf(marker) + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static MockHttpServletRequestBuilder putJson(
            String path, OidcLoginRequestPostProcessor principal, String body) {
        return put(path).with(principal).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder postJson(
            String path, OidcLoginRequestPostProcessor principal, String body) {
        return post(path)
                .with(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Marketplace register(String name) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of());
        return marketplaceRepository.register(
                name,
                upstream.toAbsolutePath().toString(),
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null);
    }
}
