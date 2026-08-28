package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The machine API chain's authentication filter (GW_0127): reads {@code Authorization: Bearer},
 * authenticates it, and holds the result for this request only.
 *
 * <p>Three properties this filter exists to guarantee, none of which is a configuration option:
 *
 * <ul>
 *   <li><b>No session, ever.</b> The security context is set on the holder and cleared in a
 *       {@code finally}; nothing is written to a {@code HttpSession} and no cookie is set. Every
 *       request carries its own credential, which is what earns this chain the same CSRF
 *       exemption the facade and publication chains have.
 *   <li><b>No ambiguous request.</b> A request presenting both a bearer credential and a
 *       {@code Cookie} header is refused outright rather than resolved to either. Ambiguity
 *       about which credential authorised a request is where confused-deputy defects live, and
 *       refusing costs a legitimate machine client nothing. The cost that is real: a client
 *       behind a load balancer that injects its own affinity cookie is refused too, which is
 *       documented in the API reference rather than left to be discovered.
 *   <li><b>One indistinguishable refusal.</b> A malformed value, an unknown one and a perfectly
 *       valid facade token all produce the same bare 401, so nothing here tells a caller which
 *       kind of wrong their credential was.
 * </ul>
 */
public class MachineApiAuthenticationFilter extends OncePerRequestFilter {

    /** The only scheme this chain accepts; the facade's Basic is deliberately not admitted. */
    static final String BEARER = "Bearer ";

    private final AuthenticationManager authenticationManager;

    public MachineApiAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    @Requirements({"GW_0127"})
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.COOKIE) != null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        String secret = bearerValue(request);
        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(new MachineApiCredentials(secret));
        } catch (AuthenticationException e) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Nothing survives the request: no session, no repository, no cookie.
            SecurityContextHolder.clearContext();
        }
    }

    /** The credential after the scheme, or the empty string; both are refused the same way. */
    static String bearerValue(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !hasBearerScheme(header)) {
            return "";
        }
        String trimmed = header.stripLeading();
        // A bare "Bearer" with nothing after it is a credential of zero length, not an error to
        // report differently: it refuses through the same path as every other bad value.
        return trimmed.length() <= BEARER.length()
                ? ""
                : trimmed.substring(BEARER.length()).trim();
    }

    /**
     * Whether a request presents a bearer credential at all — the machine chain's matcher. A
     * browser request without one falls through to the session chain exactly as before, which is
     * what keeps the web surface unchanged.
     */
    public static boolean presentsBearerCredential(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && hasBearerScheme(header);
    }

    private static boolean hasBearerScheme(String header) {
        // Case-insensitive on the scheme, as RFC 7235 requires, and deliberately also matching a
        // bare "Bearer" with nothing after it: an empty credential must be refused by this chain
        // rather than fall through to the session chain and be answered differently.
        String trimmed = header.stripLeading();
        return trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length()) || trimmed.equalsIgnoreCase(BEARER.strip());
    }
}
