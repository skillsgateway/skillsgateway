package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.MachineApiRegistry;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.persistence.Marketplace;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The negative guarantee (GW_0127), written adversarially: for every capability the machine chain
 * grants, the assertion that it is <em>not</em> granted elsewhere comes first.
 *
 * <p>The headline case leads: a token with an <b>empty fetch scope list</b> — the every-marketplace
 * form, the most permissive fetch grant the system has — reaches no {@code /api/**} endpoint at
 * all. An implementation that reasoned "no scopes means unrestricted" would hand exactly that
 * token the entire control plane.
 *
 * <p><b>Every refusal here is paired with a positive control</b> — the same route, reached by a
 * properly scoped machine credential. Without it these assertions would be one-sided, and a
 * one-sided invariant cannot catch a fail-closed defect: with the machine chain deleted entirely,
 * every refusal below still holds, because the session chain answers 401 to anything without a
 * cookie. The pairing is what makes each test distinguish "this credential is refused" from
 * "everything is refused", which is the property actually being claimed. This was found by
 * deleting the chain and watching the suite stay green; the run is in the change's evidence
 * report.
 */
class MachineCredentialNegativeTests extends AbstractGatewayTest {

    private static Instant soon() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    private TokenService.IssuedToken machineCredential(List<String> scopes) {
        return tokenService.createMachineCredential(
                uniqueName("machine"), "negative-suite", scopes, soon(), "admin@example.invalid");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String secret) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    /** Every reachable route, as a request the walk can actually issue. */
    private static List<MockHttpServletRequestBuilder> everyReachableRouteAsGet() {
        return MachineApiRegistry.reachableRoutes().stream()
                .map(entry -> entry.getValue())
                .filter(route -> route.method().equals("GET"))
                .map(route -> get(route.pattern()
                        .replace("{id}", "999999")
                        .replace("{name}", "no-such-marketplace")))
                .toList();
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void an_every_marketplace_fetch_token_reaches_no_api_endpoint() throws Exception {
        // The most permissive fetch grant that exists: no scopes at all, which means every
        // marketplace through the facade.
        TokenService.IssuedToken unscoped = tokenService.create("alice", "unscoped-fetch");
        assertThat(tokenService.authenticate(unscoped.token()).orElseThrow().permitsMarketplace("anything"))
                .as("it really is the every-marketplace form")
                .isTrue();

        for (MockHttpServletRequestBuilder route : everyReachableRouteAsGet()) {
            mockMvc.perform(bearer(route, unscoped.token())).andExpect(status().isUnauthorized());
        }
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_named_fetch_scope_token_reaches_no_api_endpoint() throws Exception {
        String name = uniqueName("fetchscope");
        registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        TokenService.IssuedToken scoped = tokenService.create("alice", "scoped-fetch", List.of(name), null);

        for (MockHttpServletRequestBuilder route : everyReachableRouteAsGet()) {
            mockMvc.perform(bearer(route, scoped.token())).andExpect(status().isUnauthorized());
        }
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_push_scoped_token_reaches_no_api_endpoint() throws Exception {
        String name = uniqueName("hosted");
        marketplaceRepository.register(
                name, null, null, Marketplace.ORIGIN_HOSTED, "append-only", null);
        TokenService.IssuedToken pusher =
                tokenService.create("alice", "pusher", List.of(), null, List.of(name));
        assertThat(tokenService.authenticate(pusher.token()).orElseThrow().permitsPushTo(name))
                .isTrue();

        for (MockHttpServletRequestBuilder route : everyReachableRouteAsGet()) {
            mockMvc.perform(bearer(route, pusher.token())).andExpect(status().isUnauthorized());
        }
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_session_derived_credential_reaches_no_api_endpoint() throws Exception {
        TokenService.IssuedToken session = tokenService.createSessionCredential("alice", "laptop", List.of());

        for (MockHttpServletRequestBuilder route : everyReachableRouteAsGet()) {
            mockMvc.perform(bearer(route, session.token())).andExpect(status().isUnauthorized());
        }
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void an_api_only_credential_is_refused_by_the_facade_and_the_publication_chain() throws Exception {
        String name = uniqueName("facade");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        TokenService.IssuedToken machine = machineCredential(List.of("marketplaces:read"));

        // Task 6.5a, at the facade rather than in the record: the credential has an empty fetch
        // scope list, which for any other token would mean every marketplace.
        GitResult clone = gitClone(facadeUrl(name, machine.token()), newWorkDir("machine-clone"));
        assertThat(clone.exitCode()).as(clone.output()).isNotZero();

        // And the publication chain, which asks the push dimension, refuses it too. A hosted
        // marketplace, so the path genuinely exists and the refusal is about the credential: the
        // publication chain answers 404 rather than 403 on purpose, so a caller without push
        // scope cannot enumerate which hosted marketplaces there are.
        String hosted = uniqueName("hosted");
        marketplaceRepository.register(hosted, null, null, Marketplace.ORIGIN_HOSTED, "append-only", null);
        assertThat(tokenService.authenticate(machine.token()).orElseThrow().permitsPushTo(hosted))
                .as("API scope confers no publication authority")
                .isFalse();
        mockMvc.perform(get("/publish/{name}/info/refs", hosted)
                        .header(HttpHeaders.AUTHORIZATION, basic(machine.token()))
                        .param("service", "git-receive-pack"))
                .andExpect(status().isNotFound());
    }

    @Test
    @SVCs({"SVC_GW_0064"})
    void an_ordinary_fetch_token_with_an_empty_scope_list_still_clones_every_marketplace() throws Exception {
        // The regression guard on the conditional fetch default (task 6.5c) at the facade: the
        // pre-existing every-marketplace meaning is preserved for every credential holding no
        // API scope, which is every credential that exists today.
        String name = uniqueName("preserved");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        GitResult clone = gitClone(facadeUrl(name, newPat()), newWorkDir("fetch-clone"));
        assertThat(clone.exitCode()).as(clone.output()).isZero();
    }

    @Test
    @SVCs({"SVC_GW_0127", "SVC_GW_0131"})
    void a_revoked_or_expired_machine_credential_is_refused() throws Exception {
        TokenService.IssuedToken revoked = machineCredential(List.of("marketplaces:read"));
        mockMvc.perform(bearer(get("/api/marketplaces"), revoked.token())).andExpect(status().isOk());
        assertThat(tokenService.revokeMachineCredential(revoked.id())).isTrue();
        // Immediate: checked at authentication time, so it takes effect on the very next request
        // rather than when some cache happens to expire.
        mockMvc.perform(bearer(get("/api/marketplaces"), revoked.token()))
                .andExpect(status().isUnauthorized());

        TokenService.IssuedToken expired = tokenService.createMachineCredential(
                uniqueName("expiring"),
                "already-over",
                List.of("marketplaces:read"),
                Instant.now().plusMillis(1),
                "admin@example.invalid");
        Thread.sleep(20);
        mockMvc.perform(bearer(get("/api/marketplaces"), expired.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_request_carrying_both_a_bearer_credential_and_a_cookie_is_refused() throws Exception {
        TokenService.IssuedToken machine = machineCredential(List.of("marketplaces:read"));

        mockMvc.perform(bearer(get("/api/marketplaces"), machine.token())
                        .header(HttpHeaders.COOKIE, "JSESSIONID=whatever"))
                .andExpect(status().isUnauthorized());
        // The same credential alone is fine, so the refusal is the ambiguity and nothing else.
        mockMvc.perform(bearer(get("/api/marketplaces"), machine.token())).andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_bearer_request_creates_no_session_and_sets_no_cookie() throws Exception {
        TokenService.IssuedToken machine = machineCredential(List.of("marketplaces:read"));

        MockHttpServletResponse response = mockMvc.perform(bearer(get("/api/marketplaces"), machine.token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.getCookies()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0011"})
    void a_browser_session_without_a_bearer_header_reaches_the_api_exactly_as_before() throws Exception {
        // The session chain is untouched: unauthenticated is still 401, and a login still works.
        mockMvc.perform(get("/api/marketplaces")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/marketplaces").with(oidcLogin())).andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_garbage_an_empty_and_a_valid_facade_bearer_value_are_indistinguishable() throws Exception {
        TokenService.IssuedToken facadeToken = tokenService.create("alice", "facade-only");

        String garbage = body(mockMvc.perform(bearer(get("/api/marketplaces"), "sgw_definitely-not-a-token"))
                .andExpect(status().isUnauthorized()));
        String empty = body(mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized()));
        String valid = body(mockMvc.perform(bearer(get("/api/marketplaces"), facadeToken.token()))
                .andExpect(status().isUnauthorized()));

        assertThat(garbage).isEqualTo(empty).isEqualTo(valid);
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_facade_token_presented_as_basic_does_not_authenticate_the_api() throws Exception {
        TokenService.IssuedToken facadeToken = tokenService.create("alice", "basic-attempt");

        // Basic is the facade's scheme, and the machine chain does not match it at all, so the
        // request falls through to the session chain and is unauthenticated there.
        mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, basic(facadeToken.token())))
                .andExpect(status().isUnauthorized());
        // A machine credential presented as Basic fares no better: the scheme is the matcher.
        mockMvc.perform(get("/api/marketplaces")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                basic(machineCredential(List.of("marketplaces:read")).token())))
                .andExpect(status().isUnauthorized());
        reachesTheApi(machineCredential(List.of("marketplaces:read")));
    }

    /**
     * The positive control every refusal above is paired with: the machine chain does authenticate
     * a properly scoped credential on the very same route. Without this, each refusal would hold
     * just as well with the chain deleted.
     */
    private void reachesTheApi(TokenService.IssuedToken machine) throws Exception {
        mockMvc.perform(bearer(get("/api/marketplaces"), machine.token())).andExpect(status().isOk());
    }

    private static String basic(String secret) {
        return "Basic "
                + Base64.getEncoder().encodeToString(("gateway:" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
