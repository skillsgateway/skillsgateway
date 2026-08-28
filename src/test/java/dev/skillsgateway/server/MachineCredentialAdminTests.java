package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.MachineApiAuthentication;
import dev.skillsgateway.server.auth.TokenService;
import dev.skillsgateway.server.roles.ClaimRoleMapper;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * Provisioning is admin-only whether or not role enforcement is enabled (GW_0130), and this suite
 * runs with the flag at its <b>default of disabled</b> — the state in which every other
 * {@code require*} check passes and any user who completed a login would otherwise be able to mint
 * a credential holding every scope.
 *
 * <p>The reason that matters: the credential outlives the session that created it. An employee who
 * leaves takes nothing with them; a credential they minted stays, working long after their
 * identity-provider account is deprovisioned. That is a persistence of privilege the session
 * itself does not have.
 */
@TestPropertySource(properties = {"skills-gateway.roles.admins=owner"})
class MachineCredentialAdminTests extends AbstractGatewayTest {

    @Autowired
    private ClaimRoleMapper claimRoleMapper;

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.subject("owner"));
    }

    private static OidcLoginRequestPostProcessor anyone() {
        return oidcLogin().idToken(token -> token.subject("just-logged-in"));
    }

    private static String body(String principal) {
        return "{\"principal\": \"%s\", \"name\": \"p\", \"apiScopes\": [\"estate:read\"], \"expiresAt\": \"%s\"}"
                .formatted(principal, Instant.now().plus(10, ChronoUnit.DAYS));
    }

    @Test
    @SVCs({"SVC_GW_0130"})
    void minting_listing_and_revoking_require_the_admin_role_even_with_enforcement_disabled() throws Exception {
        // The flag really is off: an ordinary session passes every other privileged check.
        mockMvc.perform(get("/api/roles").with(anyone())).andExpect(status().isOk());

        mockMvc.perform(post("/api/tokens/machine")
                        .with(anyone())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueName("nope"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tokens/machine").with(anyone())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tokens/machine/{id}", 999999).with(anyone()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tokens/machine/{id}/rotate", 999999).with(anyone()))
                .andExpect(status().isForbidden());

        // And the configured admin can do all four, so the refusal is the role and not the route.
        String principal = uniqueName("minted");
        String created = mockMvc.perform(post("/api/tokens/machine")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(principal)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.<Number>read(created, "$.id").longValue();
        mockMvc.perform(get("/api/tokens/machine").with(admin())).andExpect(status().isOk());
        mockMvc.perform(post("/api/tokens/machine/{id}/rotate", id).with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    @SVCs({"SVC_GW_0130"})
    void a_machine_credential_derives_no_role_from_identity_provider_claims() {
        TokenService.IssuedToken machine = tokenService.createMachineCredential(
                uniqueName("claimless"),
                "no-claims",
                List.of("estate:read"),
                Instant.now().plus(10, ChronoUnit.DAYS),
                "owner");
        var authentication =
                new MachineApiAuthentication(tokenService.authenticate(machine.token()).orElseThrow());

        // Not a gap to close: a credential has no claims, and the design depends on it. The only
        // route to a machine role is configuration or a declared grant.
        assertThat(claimRoleMapper.rolesFrom(authentication)).isEmpty();
    }
}
