package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The CSRF token on the one chain that carries an ambient credential (GW_AUTH_0030).
 *
 * <p>This suite exists because {@link AbstractGatewayTest} gives every request a token by default,
 * which is what keeps 143 mutating call sites in the other suites about their own subject. The
 * consequence is that none of them can fail for a missing token, so the requirement is proved here
 * or nowhere.
 *
 * <p>The route is a body-less {@code POST}: that is the shape a plain cross-site HTML form can
 * produce, and it is what the JSON content-type requirement does not stop. The session is an
 * administrator, so a refusal here can only be the token — a role failure would answer 403 too.
 *
 * <p>What the cookies themselves say is asserted in {@link SessionCookieTests} instead, and has to
 * be: {@code csrf()} reflectively replaces the application's {@code CookieCsrfTokenRepository} with
 * a session-backed one, and that replacement lands on the cached context's filter chain for the
 * rest of the JVM. Suites that predate this one already use it, so in this context there is no
 * cookie repository left to observe.
 */
class CsrfEnforcementTests extends AbstractGatewayTest {

    /** MockMvc without the base class's default token: the absence is this suite's subject. */
    private MockMvc withoutDefaultToken() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0030"})
    void a_body_less_mutation_on_an_admin_session_is_refused_without_a_token_and_accepted_with_one() throws Exception {
        var admin = oidcLogin().idToken(token -> token.subject("root"));

        withoutDefaultToken().perform(post("/api/catalog/rebuild").with(admin)).andExpect(status().isForbidden());

        withoutDefaultToken()
                .perform(post("/api/catalog/rebuild").with(admin).with(csrf()))
                .andExpect(status().isOk());
    }
}
