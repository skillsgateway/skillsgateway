package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The two ways the escape hatch's administrative role must <em>not</em> be reachable (GW_0141).
 *
 * <p>Conferring a role on a principal by name is the kind of shortcut that quietly becomes a
 * privilege escalation, so both of the obvious routes are closed here rather than argued about in a
 * comment. This context leaves the escape hatch off, which is every real deployment.
 */
@TestPropertySource(
        // Someone has to be an admin for the gateway to start (GW_0139), and it is not "dev".
        properties = {"skills-gateway.roles.admins=real-admin"})
class EscapeHatchRoleNegativeTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0141"})
    void a_principal_named_dev_holds_nothing_while_the_escape_hatch_is_off() throws Exception {
        var pretender = oidcLogin().idToken(token -> token.subject("dev"));

        mockMvc.perform(get("/api/me").with(pretender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty());
        mockMvc.perform(post("/api/marketplaces")
                        .with(pretender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"url\": \"https://example.com/x.git\"}"
                                .formatted(uniqueName("pretend"))))
                .andExpect(status().isForbidden());
    }

    /**
     * An identity-provider session is not the synthetic principal even when it shares its name. The
     * hatch replaces the provider rather than coexisting with it, so a session that arrived through
     * a provider did not arrive through the hatch — and a directory where anyone can be called
     * {@code dev} must not be a way in.
     */
    @Test
    @SVCs({"SVC_GW_0141"})
    void an_identity_provider_session_named_dev_gains_nothing_by_that_path() throws Exception {
        // Same name, real OIDC principal. Asserted separately from the case above because the two
        // are closed by different conditions in the check, and a single test would pass with either
        // one of them removed.
        mockMvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token.subject("dev"))))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.roles[?(@.source == 'dev-insecure-auth')]").isEmpty());
    }
}
