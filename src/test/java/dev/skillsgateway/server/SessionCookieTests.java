package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the two cookies say, and that the token is required, asked of the running server over real
 * HTTP (GW_AUTH_0030).
 *
 * <p>This suite owns its context, and that is the whole reason it exists rather than being two more
 * methods on {@link CsrfEnforcementTests}. {@code SecurityMockMvcRequestPostProcessors.csrf()}
 * reflectively swaps the application's {@code CookieCsrfTokenRepository} for a session-backed one,
 * and the swap lands on the cached context's {@code FilterChainProxy} permanently. Any context a
 * suite has used it in no longer has the cookie repository the portal depends on, so no assertion
 * made there could tell a working deployment from a broken one.
 *
 * <p>Nothing here uses MockMvc, for the same reason.
 *
 * <p>Enforcement itself is not asserted here but in {@link CsrfEnforcementTests}, and that division
 * is forced rather than chosen. A refusal for want of a token reaches an <em>anonymous</em> caller
 * as this chain's 401, not a 403, so over real HTTP with no session the status cannot tell a
 * missing token from a missing session. The case that discriminates needs a session, which is what
 * that suite gives it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Kept in lock-step with AbstractForwardedHeadersTest so the two share one application
        // context rather than adding a thirty-third (ContextBudgetTests). The posture that suite
        // needs is the posture this one needs — a real server, a configured provider that is never
        // reached, and the authorization redirect as the probe — and it uses no MockMvc, so its
        // context still holds the real cookie repository. If this list drifts from that one the
        // budget test says so, loudly, which is the coupling working rather than failing.
        properties = {
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.data-dir=target/test-git-data",
            "skills-gateway.roles.admins=user",
            "spring.main.cloud-platform=none"
        })
class SessionCookieTests {

    @LocalServerPort
    private int port;

    private HttpResponse<Void> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * The probe is the login redirect, which is also the request {@code SameSite=Strict} would
     * break: the identity provider sends the browser back to {@code /login/oauth2/code/*} as a
     * cross-site top-level navigation, and the session it must carry is the one holding the saved
     * authorization request. Asserting {@code Lax} here is asserting that login still works.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0030"})
    void the_login_redirect_writes_a_readable_token_cookie_and_a_lax_session_cookie() throws Exception {
        HttpResponse<Void> response = send(
                HttpRequest.newBuilder(uri("/oauth2/authorization/idp")).GET().build());

        assertThat(response.statusCode()).isEqualTo(302);
        List<String> cookies = response.headers().allValues("set-cookie");

        assertThat(cookies)
                .as("the portal's own script has to read the token, so it cannot be HttpOnly")
                .filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .singleElement()
                .asString()
                .doesNotContain("HttpOnly");

        assertThat(cookies)
                .as("Lax, not Strict: Strict withholds this cookie on the redirect back from the provider")
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .singleElement()
                .asString()
                .contains("SameSite=Lax");
    }
}
