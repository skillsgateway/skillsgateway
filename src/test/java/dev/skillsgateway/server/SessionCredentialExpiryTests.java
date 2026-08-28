package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * A session credential dies on its own (GW_0104, GW_0065). The lifetime here is one millisecond,
 * so the credential is already past it by the time it is presented — which is the point: expiry is
 * a comparison at authentication time, so nothing has to run for it to take effect.
 */
@TestPropertySource(properties = {"skills-gateway.tokens.session-ttl=1ms"})
class SessionCredentialExpiryTests extends AbstractGatewayTest {

    @Autowired
    private TokenService tokenService;

    @Test
    @SVCs({"SVC_GW_0104"})
    void an_elapsed_session_credential_fails_authentication_with_no_sweep_involved() throws Exception {
        String minted = mockMvc.perform(post("/api/tokens/session")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"laptop\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(minted, "$.token");

        // No scheduler has run, nothing has been swept, and the credential is already dead.
        assertThat(tokenService.authenticate(token)).isEmpty();

        Registered fixture = registerAndIngest(uniqueName("deadcred"), createUpstream(DEFAULT_MANIFEST));
        approve(fixture.snapshot().id());
        assertThat(gitClone(facadeUrl(fixture.marketplace().name(), token), newWorkDir("dead"))
                        .exitCode())
                .isNotZero();
    }
}
