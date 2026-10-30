package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.SessionBoundCsrfTokenRepository;
import io.github.reqstool.annotations.SVCs;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The web chain's token repository on its own (GW_AUTH_0058): the session holds the token, the
 * cookie is a copy written for the portal's script, and a request without a session gets neither.
 */
class SessionBoundCsrfTokenRepositoryTests {

    private static final RequestMatcher SIGN_IN = request -> "/sign-in".equals(request.getRequestURI());

    private final SessionBoundCsrfTokenRepository repository = new SessionBoundCsrfTokenRepository(SIGN_IN);

    private static MockHttpServletRequest withSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.getSession(true);
        return request;
    }

    private static List<String> tokenCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith(SessionBoundCsrfTokenRepository.COOKIE_NAME + "="))
                .toList();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void a_saved_token_is_stored_in_the_session_and_copied_to_a_same_site_script_readable_cookie() {
        MockHttpServletRequest request = withSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN");
        assertThat(repository.loadToken(request))
                .extracting(CsrfToken::getToken)
                .isEqualTo(token.getToken());
        assertThat(tokenCookies(response))
                .singleElement()
                .asString()
                .startsWith("XSRF-TOKEN=" + token.getToken() + ";")
                .contains("Path=/")
                .contains("SameSite=Lax")
                .doesNotContain("HttpOnly")
                .doesNotContain("Secure");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void the_cookie_is_secure_when_the_request_is() {
        MockHttpServletRequest request = withSession();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveToken(repository.generateToken(request), request, response);

        assertThat(tokenCookies(response)).singleElement().asString().contains("Secure");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void a_cookie_alone_is_no_token_and_no_session_is_created_for_it() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/catalog/rebuild");
        request.setCookies(new Cookie(SessionBoundCsrfTokenRepository.COOKIE_NAME, "chosen-by-the-sender"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(repository.loadToken(request)).isNull();
        DeferredCsrfToken deferred = repository.loadDeferredToken(request, response);

        assertThat(deferred.isGenerated()).isTrue();
        assertThat(deferred.get().getToken()).isNotEqualTo("chosen-by-the-sender");
        assertThat(request.getSession(false)).as("no session was created").isNull();
        assertThat(tokenCookies(response)).as("nothing was issued").isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void with_a_session_the_stored_token_wins_over_the_cookie_and_the_copy_is_repaired() {
        MockHttpServletRequest first = withSession();
        CsrfToken stored = repository.generateToken(first);
        repository.saveToken(stored, first, new MockHttpServletResponse());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setSession(first.getSession());
        request.setCookies(new Cookie(SessionBoundCsrfTokenRepository.COOKIE_NAME, "overwritten"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        DeferredCsrfToken deferred = repository.loadDeferredToken(request, response);

        assertThat(deferred.get().getToken()).isEqualTo(stored.getToken());
        assertThat(deferred.isGenerated()).isFalse();
        assertThat(tokenCookies(response))
                .as("the copy is written again with the session's token")
                .singleElement()
                .asString()
                .startsWith("XSRF-TOKEN=" + stored.getToken() + ";");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void a_matching_copy_is_not_rewritten() {
        MockHttpServletRequest first = withSession();
        CsrfToken stored = repository.generateToken(first);
        repository.saveToken(stored, first, new MockHttpServletResponse());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setSession(first.getSession());
        request.setCookies(new Cookie(SessionBoundCsrfTokenRepository.COOKIE_NAME, stored.getToken()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(repository.loadDeferredToken(request, response).get().getToken())
                .isEqualTo(stored.getToken());
        assertThat(tokenCookies(response)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void removing_the_token_clears_the_session_and_expires_the_copy() {
        MockHttpServletRequest request = withSession();
        repository.saveToken(repository.generateToken(request), request, new MockHttpServletResponse());
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveToken(null, request, response);

        assertThat(repository.loadToken(request)).isNull();
        assertThat(tokenCookies(response))
                .singleElement()
                .asString()
                .startsWith("XSRF-TOKEN=;")
                .contains("Max-Age=0");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0060"})
    void a_sign_in_may_start_the_session_that_holds_the_token() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sign-in");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.loadDeferredToken(request, response).get();

        assertThat(request.getSession(false)).isNotNull();
        assertThat(repository.loadToken(request))
                .extracting(CsrfToken::getToken)
                .isEqualTo(token.getToken());
        assertThat(tokenCookies(response)).singleElement().asString().startsWith("XSRF-TOKEN=" + token.getToken());
    }
}
