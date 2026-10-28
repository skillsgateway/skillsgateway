package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An administrator lists and revokes another principal's personal tokens (GW_AUTH_0055), which is
 * what offboarding needs: the owner-scoped routes reach only the caller's own. "root" is an
 * administrator of the shared context; every other subject here holds no role.
 */
class PrincipalTokenAdministrationTests extends AbstractGatewayTest {

    private static final String ROUTE = "/api/v1/tokens/principals/{principal}";

    @Test
    @SVCs({"SVC_GW_AUTH_0055"})
    void an_administrator_lists_and_revokes_every_personal_token_of_a_principal() throws Exception {
        String leaver = uniqueName("leaver");
        TokenService.IssuedToken pat = tokenService.create(leaver, "laptop");
        TokenService.IssuedToken session = tokenService.createSessionCredential(leaver, "browser", List.of());
        var root = oidcLogin().idToken(token -> token.subject("root"));

        mockMvc.perform(get(ROUTE, leaver).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(pat.id()))
                .andExpect(jsonPath("$[0].token").doesNotExist());

        mockMvc.perform(delete(ROUTE, leaver).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(leaver))
                .andExpect(jsonPath("$.revokedTokenIds.length()").value(2));

        assertThat(tokenService.authenticate(pat.token())).as("the revoked PAT").isEmpty();
        assertThat(tokenService.authenticate(session.token()))
                .as("the revoked session credential")
                .isEmpty();
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("principal-tokens-revoked");
            assertThat(entry.get("principal")).isEqualTo("root");
            assertThat((String) entry.get("detail")).contains("principal=" + leaver);
        });

        // Nothing left to revoke is an answer, not an error.
        mockMvc.perform(delete(ROUTE, leaver).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedTokenIds.length()").value(0));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0055"})
    void a_machine_credential_of_the_same_principal_is_left_alone() throws Exception {
        String shared = uniqueName("shared");
        TokenService.IssuedToken machine = tokenService.createMachineCredential(
                shared, "pipeline", List.of("estate:read"), Instant.now().plus(7, ChronoUnit.DAYS), "root");
        TokenService.IssuedToken pat = tokenService.create(shared, "laptop");
        var root = oidcLogin().idToken(token -> token.subject("root"));

        mockMvc.perform(get(ROUTE, shared).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pat.id()));
        mockMvc.perform(delete(ROUTE, shared).with(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedTokenIds[0]").value(pat.id()))
                .andExpect(jsonPath("$.revokedTokenIds.length()").value(1));

        assertThat(tokenService.authenticate(machine.token()))
                .as("the machine credential")
                .isPresent();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0055"})
    void a_session_without_the_admin_role_is_refused_and_revokes_nothing() throws Exception {
        String owner = uniqueName("owner");
        TokenService.IssuedToken pat = tokenService.create(owner, "laptop");
        var nobody = oidcLogin().idToken(token -> token.subject(uniqueName("nobody")));

        mockMvc.perform(get(ROUTE, owner).with(nobody)).andExpect(status().isForbidden());
        mockMvc.perform(delete(ROUTE, owner).with(nobody)).andExpect(status().isForbidden());

        assertThat(tokenService.authenticate(pat.token())).isPresent();
    }
}
