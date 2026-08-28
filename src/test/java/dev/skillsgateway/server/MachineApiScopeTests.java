package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.MachineApiRegistry;
import dev.skillsgateway.server.auth.MachineApiRegistry.Route;
import dev.skillsgateway.server.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Scopes and the allowlist (GW_0129), with role enforcement at its <b>default of disabled</b> —
 * the state in which every {@code require*} check passes. That is deliberate and is the trap this
 * change exists to avoid: if scope enforcement consulted that flag, a machine credential would
 * inherit "everything passes" under a default nobody set.
 *
 * <p>The walks below are driven from {@link MachineApiRegistry} rather than from a hand-copied
 * list, so a scope added without a test is impossible: the parameterised walk covers whatever the
 * registry holds.
 */
class MachineApiScopeTests extends AbstractGatewayTest {

    /** 403, not 401: the credential authenticated and the allowlist refused it. */
    private static final int FORBIDDEN = 403;

    private TokenService.IssuedToken credential(List<String> scopes) {
        return tokenService.createMachineCredential(
                uniqueName("scoped"),
                "scope-walk",
                scopes,
                Instant.now().plus(30, ChronoUnit.DAYS),
                "admin@example.invalid");
    }

    /**
     * A request for a route, with a bindable body where one is needed so the walk observes the
     * authorization decision rather than a binding failure.
     */
    private static MockHttpServletRequestBuilder request(Route route, String secret) {
        String path = route.pattern().replace("{id}", "999999").replace("{name}", "no-such-marketplace");
        MockHttpServletRequestBuilder builder =
                switch (route.method()) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(route.toString());
                };
        if (route.method().equals("POST") || route.method().equals("PUT")) {
            builder.contentType(MediaType.APPLICATION_JSON).content(path.endsWith("/cursor") ? "{\"after\": 0}" : "{}");
        }
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    @Test
    @SVCs({"SVC_GW_0129"})
    void scope_enforcement_holds_with_role_enforcement_disabled() throws Exception {
        // The default flag: every require*() passes, so nothing but the allowlist and the scopes
        // stand between this credential and the endpoints it was not scoped for.
        TokenService.IssuedToken reader = credential(List.of("marketplaces:read"));

        mockMvc.perform(request(new Route("GET", "/api/marketplaces"), reader.token()))
                .andExpect(status().isOk());
        mockMvc.perform(request(new Route("GET", "/api/estate"), reader.token()))
                .andExpect(status().is(FORBIDDEN));
        mockMvc.perform(request(new Route("GET", "/api/audit"), reader.token())).andExpect(status().is(FORBIDDEN));
    }

    /**
     * The whole scope table, one scope at a time: every route of the held scope is reached, and
     * every route of every <em>other</em> scope is refused. A spot check would prove the mechanism
     * exists; this proves the granularity is real rather than decorative.
     */
    @Test
    @SVCs({"SVC_GW_0129"})
    void each_scope_reaches_its_own_routes_and_is_refused_on_every_other_scopes_routes() throws Exception {
        for (String scope : MachineApiRegistry.scopes()) {
            String secret = credential(List.of(scope)).token();

            for (Route route : MachineApiRegistry.routesOf(scope)) {
                mockMvc.perform(request(route, secret))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("%s holding %s", route, scope)
                                .isNotEqualTo(FORBIDDEN)
                                .isNotEqualTo(401));
            }
            for (String other : MachineApiRegistry.scopes()) {
                if (other.equals(scope)) {
                    continue;
                }
                for (Route route : MachineApiRegistry.routesOf(other)) {
                    mockMvc.perform(request(route, secret))
                            .andExpect(result -> assertThat(result.getResponse().getStatus())
                                    .as("%s holding only %s must be refused (it belongs to %s)", route, scope, other)
                                    .isEqualTo(FORBIDDEN));
                }
            }
        }
    }

    @Test
    @SVCs({"SVC_GW_0129"})
    void no_scope_implies_another() throws Exception {
        String writer = credential(List.of("policy:write")).token();
        // Writing policy rules does not confer reading them: implication chains are how coarse
        // scopes grow back, so naming both is the only way to have both.
        mockMvc.perform(request(new Route("GET", "/api/policy/rules"), writer)).andExpect(status().is(FORBIDDEN));

        String registrar = credential(List.of("marketplaces:register")).token();
        mockMvc.perform(request(new Route("GET", "/api/marketplaces"), registrar))
                .andExpect(status().is(FORBIDDEN));

        String reader = credential(List.of("marketplaces:read")).token();
        mockMvc.perform(request(new Route("POST", "/api/marketplaces"), reader)).andExpect(status().is(FORBIDDEN));
    }

    @Test
    @SVCs({"SVC_GW_0129"})
    void scopes_compose_additively_and_reach_exactly_the_union() throws Exception {
        String both = credential(List.of("marketplaces:read", "estate:read")).token();

        mockMvc.perform(request(new Route("GET", "/api/marketplaces"), both)).andExpect(status().isOk());
        mockMvc.perform(request(new Route("GET", "/api/estate"), both)).andExpect(status().isOk());
        // And nothing more: a third scope's routes stay refused.
        mockMvc.perform(request(new Route("GET", "/api/audit"), both)).andExpect(status().is(FORBIDDEN));
        mockMvc.perform(request(new Route("POST", "/api/estate/reconcile"), both))
                .andExpect(status().is(FORBIDDEN));
    }

    /**
     * The adversarial case: every scope the gateway has, held at once. Reach is the intersection
     * of the allowlist and the scopes, never their union, so this credential still reaches nothing
     * on the unreachable table — every act of judgement, every retraction of content, every grant
     * of privilege, and every credential-minting path including the ones this change added.
     */
    @Test
    @SVCs({"SVC_GW_0129"})
    void a_credential_holding_every_scope_is_still_refused_on_every_unreachable_route() throws Exception {
        String all = credential(List.copyOf(MachineApiRegistry.scopes())).token();

        assertThat(MachineApiRegistry.unreachable()).isNotEmpty();
        for (Route route : MachineApiRegistry.unreachable()) {
            mockMvc.perform(request(route, all))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s must be unreachable to a credential holding every scope", route)
                            .isEqualTo(FORBIDDEN));
        }
    }

    /**
     * Named explicitly rather than left to the walk above, because these are the endpoints whose
     * unreachability is load-bearing: a credential that can mint a sibling can evade its own
     * revocation, and one that can approve or retract has crossed the line the whole gateway is
     * built around.
     */
    @Test
    @SVCs({"SVC_GW_0129"})
    void the_load_bearing_exclusions_are_named_and_refused_one_by_one() throws Exception {
        String all = credential(List.copyOf(MachineApiRegistry.scopes())).token();

        List<Route> exclusions = List.of(
                new Route("POST", "/api/snapshots/{id}/approve"),
                new Route("POST", "/api/snapshots/{id}/reject"),
                new Route("POST", "/api/snapshots/{id}/waivers"),
                new Route("DELETE", "/api/waivers/{id}"),
                new Route("POST", "/api/retention/evaluate"),
                new Route("POST", "/api/retention/compact"),
                new Route("DELETE", "/api/snapshots/{id}"),
                new Route("POST", "/api/snapshots/{id}/restore"),
                new Route("POST", "/api/roles"),
                new Route("DELETE", "/api/roles/{id}"),
                new Route("GET", "/api/me"),
                new Route("POST", "/api/tokens"),
                new Route("GET", "/api/tokens"),
                new Route("POST", "/api/tokens/session"),
                new Route("POST", "/api/tokens/{id}/rotate"),
                new Route("DELETE", "/api/tokens/{id}"),
                new Route("POST", "/api/tokens/machine"),
                new Route("GET", "/api/tokens/machine"),
                new Route("POST", "/api/tokens/machine/{id}/rotate"),
                new Route("DELETE", "/api/tokens/machine/{id}"));
        assertThat(MachineApiRegistry.unreachable()).containsAll(exclusions);

        for (Route route : exclusions) {
            mockMvc.perform(request(route, all))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s", route)
                            .isEqualTo(FORBIDDEN));
        }
    }

    /**
     * Re-vetting is reachable precisely because it publishes nothing: it re-runs the chain and
     * records fresh evidence, and the snapshot stays exactly as it was. Asserted rather than
     * asserted-about, because it is the one classification that could reasonably have gone the
     * other way.
     */
    @Test
    @SVCs({"SVC_GW_0129"})
    void a_revet_is_reachable_and_leaves_the_snapshot_state_untouched() throws Exception {
        Registered fixture = registerAndIngest(uniqueName("revet"), createUpstream(DEFAULT_MANIFEST));
        long snapshotId = fixture.snapshot().id();
        String stateBefore =
                snapshotRepository.findById(snapshotId).orElseThrow().state();
        String vetter = credential(List.of("vetting:run")).token();

        mockMvc.perform(post("/api/snapshots/{id}/revet", snapshotId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + vetter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // Reachable is the claim, so the assertion is that authorization did not refuse
                // it: the endpoint's own domain answer (a re-vet that is not due yet answers 409)
                // is not what this test is about.
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isNotEqualTo(FORBIDDEN)
                        .isNotEqualTo(401));

        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .as("a re-vet gates approval; it is not one")
                .isEqualTo(stateBefore);
    }

    /**
     * Retention's preview is reachable and its evaluation is not, and the difference is the
     * soft-delete: {@code evaluate} deletes every candidate it finds, which retracts content.
     */
    @Test
    @SVCs({"SVC_GW_0129"})
    void retention_candidates_is_reachable_while_evaluate_is_not() throws Exception {
        String reader = credential(List.of("retention:read")).token();

        mockMvc.perform(request(new Route("GET", "/api/retention/candidates"), reader))
                .andExpect(status().isOk());
        // Not even with every scope there is.
        String all = credential(List.copyOf(MachineApiRegistry.scopes())).token();
        mockMvc.perform(request(new Route("POST", "/api/retention/evaluate"), all))
                .andExpect(status().is(FORBIDDEN));
        mockMvc.perform(request(new Route("POST", "/api/retention/compact"), all))
                .andExpect(status().is(FORBIDDEN));
    }
}
