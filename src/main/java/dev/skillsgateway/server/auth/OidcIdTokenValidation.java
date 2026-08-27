package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;

/**
 * ID-token validation for the browser login (GW_0100).
 *
 * <p>Spring Security compares a token's {@code iss} only when the client registration carries an
 * issuer, and ours cannot: {@code application.yaml} gives the provider explicit endpoints so the
 * registration exists at AOT build time, and setting {@code issuer-uri} would send Spring Boot
 * through metadata discovery only to overwrite the discovered endpoints with those same explicit
 * values. So the comparison is added here instead, alongside — never in place of — the standard
 * checks.
 *
 * <p>This matters most where one authorization endpoint serves many tenants: every tenant's tokens
 * verify against the same signing keys, so the issuer is the only thing that says which
 * organisation the person logging in belongs to.
 */
public final class OidcIdTokenValidation {

    private OidcIdTokenValidation() {}

    /**
     * The standard OIDC checks, plus an issuer comparison when one is configured.
     *
     * @param expectedIssuer the issuer to require, or null to keep only the standard checks —
     *     which compare no issuer at all, which is why the gateway warns when nothing is pinned
     */
    @Requirements({"GW_0100"})
    public static OAuth2TokenValidator<Jwt> validator(ClientRegistration registration, String expectedIssuer) {
        OAuth2TokenValidator<Jwt> standard = new OidcIdTokenValidator(registration);
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            return standard;
        }
        return new DelegatingOAuth2TokenValidator<>(standard, new JwtIssuerValidator(expectedIssuer.trim()));
    }
}
