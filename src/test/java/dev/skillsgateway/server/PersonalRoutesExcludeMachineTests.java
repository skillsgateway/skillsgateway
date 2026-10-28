package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The personal-token routes reach personal tokens only (GW_AUTH_0057). A machine credential's
 * principal is a free-form name, and a person who logs in with the same name must not list, revoke
 * or rotate it; rotation used to turn it into an every-marketplace personal token.
 */
class PersonalRoutesExcludeMachineTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_AUTH_0057"})
    void a_login_named_like_a_machine_credential_cannot_list_revoke_or_rotate_it() throws Exception {
        String shared = uniqueName("pipeline");
        TokenService.IssuedToken machine = tokenService.createMachineCredential(
                shared, "ci", List.of("estate:read"), Instant.now().plus(7, ChronoUnit.DAYS), "root");
        var person = oidcLogin().idToken(token -> token.subject(shared));

        String listed = mockMvc.perform(get("/api/v1/tokens").with(person))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> ids = JsonPath.read(listed, "$[*].id");
        assertThat(ids).doesNotContain((int) machine.id());

        mockMvc.perform(post("/api/v1/tokens/{id}/rotate", machine.id()).with(person))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/tokens/{id}", machine.id()).with(person))
                .andExpect(status().isNotFound());

        assertThat(tokenService.authenticate(machine.token()))
                .as("the machine credential still works")
                .isPresent();
    }
}
