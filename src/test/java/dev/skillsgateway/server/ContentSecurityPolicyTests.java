package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The web surface's content security policy (GW_AUTH_0052), asserted as exact strings: a policy that
 * gained {@code 'unsafe-inline'} in the portal's {@code script-src} must fail here, not pass a
 * "contains default-src" check. Whether the portal actually runs under it is the e2e suite's
 * question, which fails any test that raises a violation.
 */
class ContentSecurityPolicyTests extends AbstractGatewayTest {

    private static final String CSP = "Content-Security-Policy";

    private static final String PORTAL = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private static final String API_REFERENCE =
            PORTAL.replace("script-src 'self';", "script-src 'self' 'unsafe-inline';");

    @Test
    @SVCs({"SVC_GW_AUTH_0052"})
    void a_portal_route_carries_the_strict_policy_and_keeps_the_other_default_headers() throws Exception {
        mockMvc.perform(get("/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(CSP, PORTAL))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0052"})
    void a_session_api_route_carries_the_strict_policy() throws Exception {
        mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(CSP, PORTAL));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0052"})
    void the_api_reference_alone_allows_inline_script() throws Exception {
        mockMvc.perform(get("/docs").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(CSP, API_REFERENCE));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0052"})
    void the_anonymous_login_redirect_carries_the_strict_policy() throws Exception {
        // A browser's Accept header: without text/html the entry point answers 401 instead.
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().stringValues(CSP, PORTAL));
    }
}
