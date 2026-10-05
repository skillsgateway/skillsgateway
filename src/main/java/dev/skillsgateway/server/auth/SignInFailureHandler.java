package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.util.HtmlUtils;

/**
 * Answers a failed browser sign-in on the spot with its reason (GW_AUTH_0054), instead of
 * redirecting to the generated login page, which says "Invalid credentials" whatever the cause.
 * Answering here rather than redirecting also keeps the reason off the session, which is the thing
 * a lost sign-in has lost.
 */
final class SignInFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SignInFailureHandler.class);

    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";
    private static final Pattern REGISTRATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Codes an authorization server sends back in the redirect: RFC 6749 §4.1.2.1, OIDC Core §3.1.2.6. */
    private static final Set<String> PROVIDER_REFUSALS = Set.of(
            "access_denied",
            "unauthorized_client",
            "invalid_request",
            "unsupported_response_type",
            "invalid_scope",
            "server_error",
            "temporarily_unavailable",
            "interaction_required",
            "login_required",
            "account_selection_required",
            "consent_required",
            "invalid_request_uri",
            "invalid_request_object",
            "request_not_supported",
            "request_uri_not_supported",
            "registration_not_supported");

    private enum Reason {
        LOST(
                "Sign-in could not be completed",
                "The gateway no longer had the sign-in this answer belongs to: it was started in another tab,"
                        + " took too long, or the gateway was restarted. Start again."),
        DECLINED(
                "The identity provider declined the sign-in",
                "Your identity provider refused it. If that is unexpected, ask whoever administers it."),
        NOT_ACCEPTED(
                "The identity provider's answer could not be accepted",
                "The gateway could not verify what the identity provider returned. An administrator can find"
                        + " the reason in the gateway's log.");

        private final String heading;
        private final String explanation;

        Reason(String heading, String explanation) {
            this.heading = heading;
            this.explanation = explanation;
        }
    }

    @Override
    @Requirements({"GW_AUTH_0054"})
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String registration = registration(request);
        OAuth2Error error = exception instanceof OAuth2AuthenticationException oauth2 ? oauth2.getError() : null;
        String code = error != null ? oneLine(error.getErrorCode(), 64) : "authentication_failed";
        String description = oneLine(error != null ? error.getDescription() : exception.getMessage(), 300);
        log.warn("browser sign-in failed: registration={} error={} description={}", registration, code, description);

        Reason reason = "authorization_request_not_found".equals(code)
                ? Reason.LOST
                : PROVIDER_REFUSALS.contains(code) ? Reason.DECLINED : Reason.NOT_ACCEPTED;
        String restart = "unknown".equals(registration) ? "/" : "/oauth2/authorization/" + registration;

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>%s</title>
                <style>body{font-family:system-ui,sans-serif;max-width:36rem;margin:4rem auto;padding:0 1rem;\
                line-height:1.5}code{background:#f3f0fa;padding:.1rem .3rem;border-radius:.2rem}</style></head>
                <body>
                <h1>%s</h1>
                <p>%s</p>
                <p>Error code: <code>%s</code></p>
                <p><a href="%s">Start sign-in again</a></p>
                </body>
                </html>
                """.formatted(
                        reason.heading,
                        reason.heading,
                        reason.explanation,
                        HtmlUtils.htmlEscape(code),
                        HtmlUtils.htmlEscape(restart)));
    }

    /** The registration the callback path names, or {@code unknown}; it ends up in a link, so it is checked. */
    private static String registration(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(CALLBACK_PREFIX)) {
            String id = path.substring(CALLBACK_PREFIX.length());
            if (REGISTRATION_ID.matcher(id).matches()) {
                return id;
            }
        }
        return "unknown";
    }

    /** Provider-supplied text, kept to one bounded line so it cannot forge a log entry. */
    private static String oneLine(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        // Line breaks named explicitly, plus the Unicode separators log viewers also break on.
        String flat = text.replaceAll("[\\r\\n\\p{Cntrl}\\u2028\\u2029]", " ").strip();
        return flat.length() > limit ? flat.substring(0, limit) + "…" : flat;
    }
}
