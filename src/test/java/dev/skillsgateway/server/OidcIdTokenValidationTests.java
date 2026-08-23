package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.OidcIdTokenValidation;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Issuer pinning for the browser login (GW_0100). Spring Security compares an ID token's {@code
 * iss} only when the client registration carries an issuer, and ours cannot carry one without
 * losing its explicitly configured endpoints — so the comparison is added here, and this is where
 * it is proved to actually refuse.
 */
class OidcIdTokenValidationTests {

    private static final String EXPECTED = "https://idp.example.com/tenant-a/v2.0";

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("idp")
                .clientId("gateway-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/idp")
                .scope("openid")
                .authorizationUri("https://idp.example.com/authorize")
                .tokenUri("https://idp.example.com/token")
                .jwkSetUri("https://idp.example.com/jwks")
                .build();
    }

    private static Jwt idToken(String issuer) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("sub", "0000-1111")
                .claim("aud", List.of("gateway-client"))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    @Test
    @SVCs({"SVC_GW_0100"})
    void a_token_from_another_issuer_is_refused_and_the_expected_one_is_accepted() {
        OAuth2TokenValidator<Jwt> validator = OidcIdTokenValidation.validator(registration(), EXPECTED);

        assertThat(validator.validate(idToken(EXPECTED)).hasErrors()).isFalse();

        // A different tenant on the same multi-tenant endpoint: same signing keys, other issuer.
        OAuth2TokenValidatorResult foreign = validator.validate(idToken("https://idp.example.com/tenant-b/v2.0"));
        assertThat(foreign.hasErrors()).isTrue();
        assertThat(foreign.getErrors())
                .anySatisfy(error -> assertThat(error.getDescription()).contains("iss"));

        // A prefix of the expected issuer is not the expected issuer.
        assertThat(validator
                        .validate(idToken("https://idp.example.com/tenant-a"))
                        .hasErrors())
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_0100"})
    void pinning_the_issuer_does_not_replace_the_standard_identity_token_checks() {
        OAuth2TokenValidator<Jwt> validator = OidcIdTokenValidation.validator(registration(), EXPECTED);

        // Right issuer, wrong audience: still refused, so the delegate is still in the chain.
        Jwt wrongAudience = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("iss", EXPECTED)
                .claim("sub", "0000-1111")
                .claim("aud", List.of("some-other-client"))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        assertThat(validator.validate(wrongAudience).hasErrors()).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_0100"})
    void with_no_issuer_configured_the_standard_checks_are_all_that_run() {
        OAuth2TokenValidator<Jwt> validator = OidcIdTokenValidation.validator(registration(), null);

        // Today's behaviour, kept deliberately: any issuer passes, which is exactly why the
        // gateway warns at startup when nothing is pinned.
        assertThat(validator
                        .validate(idToken("https://idp.example.com/tenant-b/v2.0"))
                        .hasErrors())
                .isFalse();
    }
}
