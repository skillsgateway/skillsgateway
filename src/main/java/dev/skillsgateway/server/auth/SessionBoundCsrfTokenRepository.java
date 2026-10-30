package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

/**
 * The web chain's anti-forgery token store (GW_AUTH_0058). The token lives in the server session —
 * the shared session store, so every instance sees it (GW_AUTH_0053) — and {@code XSRF-TOKEN} is a
 * copy the portal's script reads and echoes in {@code X-XSRF-TOKEN}. The cookie's value is never
 * read as a token; it is only compared with the session's to decide whether to write the copy again.
 *
 * <p>No session is created to hold a token (GW_AUTH_0059) except on a request {@code sessionMayStart}
 * matches: the request that starts a browser sign-in, which creates one anyway. Without a session
 * a generated token is stored nowhere, so a state-changing request has nothing to match and is
 * refused.
 */
@Requirements({"GW_AUTH_0058", "GW_AUTH_0059"})
public final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

    public static final String COOKIE_NAME = "XSRF-TOKEN";

    private final HttpSessionCsrfTokenRepository session = new HttpSessionCsrfTokenRepository();

    private final RequestMatcher sessionMayStart;

    public SessionBoundCsrfTokenRepository(RequestMatcher sessionMayStart) {
        this.sessionMayStart = sessionMayStart;
        session.setHeaderName("X-XSRF-TOKEN");
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return session.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            session.saveToken(null, request, response);
            writeCopy("", request, response);
            return;
        }
        if (request.getSession(false) == null && !sessionMayStart.matches(request)) {
            return;
        }
        session.saveToken(token, request, response);
        writeCopy(token.getToken(), request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return session.loadToken(request);
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return new Deferred(request, response);
    }

    /** Lax, like the session cookie: the server never reads this one, so Strict would protect nothing. */
    private static void writeCopy(String value, HttpServletRequest request, HttpServletResponse response) {
        String contextPath = request.getContextPath();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .path(contextPath.isEmpty() ? "/" : contextPath)
                .secure(request.isSecure())
                .httpOnly(false)
                .sameSite("Lax")
                .maxAge(value.isEmpty() ? 0 : -1)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * The interface's default deferral, plus one thing: when the session's token is found and the
     * request's copy differs — missing, stale, or overwritten — the copy is written again, so the
     * portal recovers on its next read instead of being refused until the session ends.
     */
    private final class Deferred implements DeferredCsrfToken {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private CsrfToken token;
        private boolean generated;

        Deferred(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public CsrfToken get() {
            init();
            return token;
        }

        @Override
        public boolean isGenerated() {
            init();
            return generated;
        }

        private void init() {
            if (token != null) {
                return;
            }
            token = loadToken(request);
            generated = token == null;
            if (generated) {
                token = generateToken(request);
                saveToken(token, request, response);
                return;
            }
            var copy = WebUtils.getCookie(request, COOKIE_NAME);
            if (copy == null || !token.getToken().equals(copy.getValue())) {
                writeCopy(token.getToken(), request, response);
            }
        }
    }
}
