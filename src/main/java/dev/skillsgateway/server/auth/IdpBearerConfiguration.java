package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The facade's identity-provider bearer path, and the only place it is switched on (GW_AUTH_0040).
 *
 * <p><b>Registered only when the capability is enabled.</b> Every bean here is conditional on
 * {@code skills-gateway.facade.idp-bearer.enabled}, so a gateway with the default configuration has
 * no decoder, no provider and no filter — not a filter that checks a flag. A trust-boundary
 * widening guarded by a branch deep inside a request path is one refactor away from opening by
 * accident; absence cannot be refactored open.
 *
 * <p><b>The identity provider is the web surface's, not a second one.</b> The key set comes from
 * the {@code idp} client registration and the issuer from {@code skills-gateway.oidc.issuer} —
 * the same two values the browser login already validates against (GW_AUTH_0017). A deployment
 * therefore cannot configure the facade to accept what its own login would refuse, because there
 * is nothing to configure differently.
 */
@Configuration
@ConditionalOnProperty(prefix = "skills-gateway.facade.idp-bearer", name = "enabled", havingValue = "true")
public class IdpBearerConfiguration {

    /** The registration id {@code application.yaml} fixes for the identity provider. */
    static final String REGISTRATION_ID = "idp";

    private static final Logger log = LoggerFactory.getLogger(IdpBearerConfiguration.class);

    /**
     * The decoder, with the checks a resource server makes and no fewer (GW_AUTH_0040).
     *
     * <p>Signature comes from {@link NimbusJwtDecoder} against the provider's published key set —
     * cached, and refreshed when a token arrives signed by a key id the cache does not hold, so a
     * routine key rotation is not an outage. The three validators are the ones a signature alone
     * does not give: <em>when</em> the token is valid ({@code exp} and {@code nbf}, with the
     * default sixty seconds of leeway, because a token refused for a skewed clock is an outage
     * that looks like a control working), <em>who</em> issued it, and <em>what</em> it was issued
     * for.
     *
     * <p>The issuer check is the reason this method can throw. On a multi-tenant authorization
     * endpoint every tenant's tokens verify against the same keys, so {@code iss} is the only
     * claim that says which organisation a holder belongs to. At login the gateway may go without
     * it, having itself exchanged a code over TLS with a configured endpoint (GW_AUTH_0017 warns and
     * continues); a bearer token arriving cold at the facade has no such provenance. So the pin is
     * a precondition here, refused at startup rather than at request time — a misconfiguration
     * whose only symptom is "tokens are accepted too broadly" is one nobody observes.
     */
    @Bean
    @Requirements({"GW_AUTH_0040"})
    public JwtDecoder idpBearerJwtDecoder(
            SkillsGatewayProperties properties, ClientRegistrationRepository registrations) {
        String issuer = properties.oidc().issuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "skills-gateway.facade.idp-bearer.enabled is true but skills-gateway.oidc.issuer is not set. "
                            + "A bearer token's issuer is the only claim that says which organisation minted it, "
                            + "so the facade will not accept one without a pinned issuer.");
        }
        ClientRegistration registration = registrations.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            throw new IllegalStateException(
                    "no '%s' client registration to take the key set from".formatted(REGISTRATION_ID));
        }
        String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException("the '%s' client registration has no jwk-set-uri; set SGW_OIDC_JWK_SET_URI"
                    .formatted(REGISTRATION_ID));
        }
        String audience = expectedAudience(properties, registration);
        log.info(
                "facade identity-provider bearer tokens are ENABLED: issuer '{}', audience '{}'",
                issuer.trim(),
                audience);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(issuer.trim()), audienceValidator(audience)));
        return decoder;
    }

    /**
     * What {@code aud} must contain: the configured value, or the OAuth2 client id.
     *
     * <p>An identity token's audience is the client id by construction, and several providers give
     * an access token the same one. Others name a separate resource identifier, which no existing
     * property holds — which is why the override exists and why it is the only other leaf this
     * capability adds.
     */
    private static String expectedAudience(SkillsGatewayProperties properties, ClientRegistration registration) {
        String configured = properties.facade().idpBearer().audience();
        return configured != null ? configured : registration.getClientId();
    }

    /**
     * Refuses a token not issued for this gateway (GW_AUTH_0040).
     *
     * <p>{@code aud} is a list per RFC 7519 §4.1.3, so containment is the check rather than
     * equality. A token carrying no audience at all is refused: without one, any application
     * registered at the same identity provider could hand the gateway an access token minted for
     * itself and have it accepted as a fetch credential.
     */
    @Requirements({"GW_AUTH_0040"})
    static OAuth2TokenValidator<Jwt> audienceValidator(String expected) {
        return token -> {
            List<String> audience = token.getAudience();
            if (audience != null && audience.contains(expected)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "the required audience is missing",
                    "https://tools.ietf.org/html/rfc6750#section-3.1"));
        };
    }

    /**
     * The principal, taken from the claim the web session takes it from (GW_AUTH_0040).
     *
     * <p>This is the whole attribution argument in one line: {@code user-name-attribute} is what
     * {@code authentication.getName()} resolves to for a browser session, so the same person
     * fetching with an SSO bearer token and with a portal-minted PAT writes the same
     * {@code principal} on the ledger. The adoption and staleness reports cannot tell the two
     * fetches apart, and do not need to.
     */
    @Bean
    @Requirements({"GW_AUTH_0040"})
    public IdpBearerAuthenticationProvider idpBearerAuthenticationProvider(
            JwtDecoder idpBearerJwtDecoder, ClientRegistrationRepository registrations) {
        ClientRegistration registration = registrations.findByRegistrationId(REGISTRATION_ID);
        String claim = registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return new IdpBearerAuthenticationProvider(idpBearerJwtDecoder, claim == null ? "sub" : claim);
    }
}
