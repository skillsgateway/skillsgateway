package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The facade's second authentication path (GW_AUTH_0040): an {@code Authorization: Bearer} token from
 * the identity provider, beside — never instead of — the PAT over Basic.
 *
 * <p>Modelled on {@link MachineApiAuthenticationFilter}, which solved the same problem on
 * {@code /api/**}, and holding the same three properties:
 *
 * <ul>
 *   <li><b>No session, ever.</b> The context is set on the holder and cleared in a
 *       {@code finally}; nothing is written to an {@code HttpSession} and no cookie is set or
 *       read. That is what keeps this chain's existing CSRF exemption honest.
 *   <li><b>It acts only when a bearer credential is present.</b> A request with Basic, or with no
 *       credential at all, passes straight through to the Basic filter and the PAT provider, so
 *       every client that works today works unchanged.
 *   <li><b>One indistinguishable refusal.</b> A refused token does not fail the request here; it
 *       leaves the context empty and lets the chain's authorization rule produce the ordinary 401
 *       with the ordinary {@code WWW-Authenticate: Basic} challenge. A git client that presented a
 *       token the gateway will not take is then told exactly what an anonymous client is told, and
 *       can fall back to the credential helper — which is the behaviour that makes an expired SSO
 *       token a prompt rather than a dead end.
 * </ul>
 */
public class FacadeBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AuthenticationManager authenticationManager;

    public FacadeBearerAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    @Requirements({"GW_AUTH_0040"})
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = bearerValue(request);
        if (secret == null) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(new IdpBearerCredentials(secret));
        } catch (AuthenticationException e) {
            // Deliberately not an error response: see the class comment. An unauthenticated
            // request reaches the chain's own 401, challenge and all.
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Nothing survives the request: no session, no cookie, no repository.
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * The credential after the scheme, or {@code null} when the request presents no bearer
     * credential at all. A bare {@code Bearer} with nothing after it is a credential of zero
     * length rather than an absent one, so it is refused through the same path as every other bad
     * value instead of falling through as if unauthenticated.
     */
    static String bearerValue(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String trimmed = header.stripLeading();
        // Case-insensitive on the scheme, as RFC 7235 requires.
        if (trimmed.equalsIgnoreCase(BEARER.strip())) {
            return "";
        }
        if (!trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return trimmed.substring(BEARER.length()).trim();
    }
}
