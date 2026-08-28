package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.TokenRepository;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * Issuance and lifecycle (GW_0126, GW_0131). Every rule here is a refusal rather than a default,
 * because every default this surface could offer produces a weaker credential than the caller
 * asked for — and the never-expiring credential in a pipeline variable is the failure mode the
 * whole capability would otherwise introduce.
 *
 * <p>The suite runs with {@code skills-gateway.tokens.max-ttl} at its default of unset, which is
 * exactly the configuration in which "mandatory expiry" would otherwise admit a hundred-year
 * credential.
 */
@TestPropertySource(properties = {"skills-gateway.roles.admins=root"})
class MachineCredentialLifecycleTests extends AbstractGatewayTest {

    /**
     * Provisioning requires the admin role whether or not role enforcement is enabled (GW_0130),
     * and this suite deliberately leaves enforcement at its default of <b>disabled</b> — the state
     * in which every other {@code require*} passes. A configuration-bootstrapped admin is the
     * session every call below uses; {@code MachineCredentialAdminTests} asserts the refusal for
     * everyone else in that same state.
     */
    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.subject("root"));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TokenRepository tokenRepository;

    private static Instant soon() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }

    private String createBody(String principal, List<String> scopes, String expiresAt) {
        String scopeJson = scopes.stream()
                .map(scope -> "\"" + scope + "\"")
                .reduce((a, b) -> a + ", " + b)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
        return "{\"principal\": \"%s\", \"name\": \"pipeline\", \"apiScopes\": %s, \"expiresAt\": %s}"
                .formatted(principal, scopeJson, expiresAt == null ? "null" : "\"" + expiresAt + "\"");
    }

    @Test
    @SVCs({"SVC_GW_0126"})
    void an_unknown_api_scope_is_refused_at_issue_time() throws Exception {
        // Misspelled: it must fail loudly, exactly as a fetch scope does, rather than silently
        // never matching anything.
        mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueName("typo"), List.of("marketplaces:regsiter"), soon().toString())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @SVCs({"SVC_GW_0126"})
    void there_is_no_wildcard_and_an_empty_scope_list_grants_nothing() throws Exception {
        for (String wildcard : List.of("*", "all", "admin", "api")) {
            mockMvc.perform(post("/api/tokens/machine")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(uniqueName("wild"), List.of(wildcard), soon().toString())))
                    .andExpect(status().isUnprocessableEntity());
        }
        // And the empty list is refused rather than quietly producing a credential that reaches
        // nothing — which would look like a working credential to whoever was handed it.
        mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueName("empty"), List.of(), soon().toString())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @SVCs({"SVC_GW_0131"})
    void issuance_without_an_expiry_is_refused_and_never_defaulted() throws Exception {
        mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueName("forever"), List.of("estate:read"), null)))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * The built-in cap, and why it exists. {@code validateTtl} returns early when no cap is
     * configured, so "mandatory expiry" on its own admits {@code now + 100 years} — the
     * never-expiring credential, spelled differently. This suite configures no cap.
     */
    @Test
    @SVCs({"SVC_GW_0131"})
    void a_lifetime_beyond_the_built_in_cap_is_refused_rather_than_shortened() throws Exception {
        Instant aCentury = Instant.now().plus(365L * 100, ChronoUnit.DAYS);

        mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueName("century"), List.of("estate:read"), aCentury.toString())))
                .andExpect(status().isUnprocessableEntity());

        // Refused, never clamped: nothing was issued with a silently shortened lifetime.
        assertThat(tokenService.listMachineCredentials())
                .noneMatch(credential -> credential.name().equals("pipeline")
                        && credential.expiresAt() != null
                        && credential.expiresAt().isAfter(Instant.now().plus(89, ChronoUnit.DAYS))
                        && credential.expiresAt().isBefore(Instant.now().plus(91, ChronoUnit.DAYS))
                        && credential.principal().startsWith("century"));

        // Just inside the cap is accepted, so the boundary is the cap and not the endpoint.
        Instant inside = Instant.now()
                .plus(SkillsGatewayProperties.Tokens.DEFAULT_MACHINE_MAX_TTL)
                .minus(1, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueName("inside"), List.of("estate:read"), inside.toString())))
                .andExpect(status().isCreated());
    }

    @Test
    @SVCs({"SVC_GW_0127"})
    void a_session_derived_credential_can_never_hold_an_api_scope() {
        // Enforced by the schema, not only by the one service method that mints them, so no
        // future call site can launder a browser session into a standing control-plane credential.
        assertThatThrownBy(() -> tokenRepositoryCreateSessionDerivedWithApiScope())
                .hasMessageContaining("session_derived_credentials_hold_no_api_scope");
    }

    private void tokenRepositoryCreateSessionDerivedWithApiScope() {
        tokenRepository.create(
                uniqueName("laundered"),
                "session-with-api-scope",
                "hash-" + uniqueName("h"),
                null,
                soon(),
                null,
                null,
                true,
                "estate:read",
                "admin@example.invalid");
    }

    /**
     * A rotation that silently widened or dropped an API scope would be the worst defect this
     * feature could have, so the assertion is per scope value rather than "the list is equal".
     */
    @Test
    @SVCs({"SVC_GW_0131"})
    void rotation_preserves_the_identity_the_deadline_and_every_api_scope_value() throws Exception {
        String principal = uniqueName("rotating");
        List<String> scopes = List.of("marketplaces:read", "estate:read", "policy:write");
        TokenService.IssuedToken original = tokenService.createMachineCredential(
                principal, "rotating-pipeline", scopes, soon(), "admin@example.invalid");

        TokenService.IssuedToken rotated =
                tokenService.rotateMachineCredential(original.id()).orElseThrow();

        AccessToken stored = tokenService.findMachineCredential(rotated.id()).orElseThrow();
        assertThat(stored.principal()).isEqualTo(principal);
        assertThat(stored.name()).isEqualTo("rotating-pipeline");
        assertThat(stored.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(stored.rotatedFrom()).isEqualTo(original.id());
        for (String scope : scopes) {
            assertThat(stored.permitsApiScope(scope))
                    .as("rotation kept %s", scope)
                    .isTrue();
        }
        assertThat(stored.apiScopeList()).as("and added nothing").containsExactlyInAnyOrderElementsOf(scopes);
        // Fetch and push stay empty: rotation cannot turn a control-plane credential into a
        // content credential either.
        assertThat(stored.scopeList()).isEmpty();
        assertThat(stored.pushScopeList()).isEmpty();

        // The old secret is dead before the new one is returned: no moment has two live secrets.
        assertThat(tokenService.authenticate(original.token())).isEmpty();
        assertThat(tokenService.authenticate(rotated.token())).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_0131"})
    void revocation_takes_effect_on_the_very_next_request() throws Exception {
        TokenService.IssuedToken machine = tokenService.createMachineCredential(
                uniqueName("revoked"), "doomed", List.of("marketplaces:read"), soon(), "admin@example.invalid");

        mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tokens/machine/{id}", machine.id()).with(admin()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(tokenService.revokeMachineCredential(machine.id())).isTrue();
        // No sleep: expiry and revocation are compared at authentication time, not swept.
        mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + machine.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SVCs({"SVC_GW_0131"})
    void the_administrative_listing_covers_every_machine_credential_and_leaves_own_tokens_alone() throws Exception {
        String principal = uniqueName("listed");
        tokenService.createMachineCredential(
                principal, "listed-pipeline", List.of("estate:read"), soon(), "someone-else@example.invalid");
        // A personal access token belonging to somebody entirely different.
        tokenService.create("alice", "alices-pat");

        String body = mockMvc.perform(get("/api/tokens/machine").with(admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains(principal).contains("someone-else@example.invalid");
        // Never a secret, and never the stored hash.
        assertThat(body).doesNotContain("token_hash").doesNotContain("tokenHash");
        assertThat(MAPPER.readTree(body))
                .allSatisfy(node -> assertThat(node.has("apiScopes")).isTrue());

        // The caller's own-token listing is untouched and still strictly own-principal: the
        // machine credential above belongs to nobody who logs in, so it must not appear there.
        String own = mockMvc.perform(get("/api/tokens").with(admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(own).doesNotContain(principal);
    }
}
