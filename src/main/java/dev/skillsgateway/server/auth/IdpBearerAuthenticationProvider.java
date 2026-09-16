package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Authenticates a facade request presenting an identity-provider bearer token (GW_AUTH_0040).
 *
 * <p>Every way a token can be wrong — unparseable, signed by an unknown key, from another issuer,
 * for another audience, expired, not yet valid, missing the principal claim, or a personal access
 * token that is not a JWT at all — leaves through this one {@code BadCredentialsException} with
 * one message. The caller learns that the credential was refused and nothing about which kind of
 * wrong it was; the facade's existing Basic path already behaves this way, and a bearer path that
 * distinguished its refusals would be an oracle bolted to the side of it.
 */
public class IdpBearerAuthenticationProvider implements AuthenticationProvider {

    private final JwtDecoder decoder;
    private final String principalClaim;

    public IdpBearerAuthenticationProvider(JwtDecoder decoder, String principalClaim) {
        this.decoder = decoder;
        this.principalClaim = principalClaim;
    }

    @Override
    @Requirements({"GW_AUTH_0040"})
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Object credentials = authentication.getCredentials();
        if (credentials == null || credentials.toString().isBlank()) {
            throw new BadCredentialsException("invalid token");
        }
        Jwt token;
        try {
            token = decoder.decode(credentials.toString());
        } catch (JwtException e) {
            throw new BadCredentialsException("invalid token", e);
        }
        String principal = token.getClaimAsString(principalClaim);
        if (principal == null || principal.isBlank()) {
            // A token that validates but names nobody is refused rather than attributed to a
            // blank: the ledger's whole claim is that a fetch is attributable, and an entry with
            // an empty principal would be a fetch pretending to be one.
            throw new BadCredentialsException("invalid token");
        }
        return new IdpBearerAuthentication(principal, token);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return IdpBearerCredentials.class.isAssignableFrom(authentication);
    }
}
