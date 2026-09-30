package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * The browser login's ID-token decoder, and the refusal to build one that compares no issuer for a
 * configured identity provider (GW_AUTH_0017).
 *
 * <p>Kept out of {@link SecurityConfig} so the refusal can be exercised as a real context without
 * the rest of the security configuration.
 */
@Configuration(proxyBeanMethods = false)
public class IdTokenDecoderConfiguration {

    /**
     * Adds the issuer comparison Spring Security cannot make on its own here, and refuses to start
     * without one once a provider is configured. "Configured" is the escape-hatch guard's
     * definition, so the shipped placeholders are the one state that starts unpinned. Under the
     * escape hatch that guard makes the refusal, and its message is the one to read.
     */
    @Bean
    @Requirements({"GW_AUTH_0017"})
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(
            SkillsGatewayProperties properties, ObjectProvider<ClientRegistrationRepository> registrations) {
        String issuer = properties.oidc().issuer();
        if ((issuer == null || issuer.isBlank()) && !properties.devInsecureAuth()) {
            List<String> signals = DevInsecureAuthGuard.identityProviderSignals(null, registrations.getIfAvailable());
            if (!signals.isEmpty()) {
                throw new IllegalStateException(refusal(signals));
            }
        }
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> OidcIdTokenValidation.validator(registration, issuer));
        return factory;
    }

    private static String refusal(List<String> signals) {
        String evidence = signals.stream().map(signal -> "  - " + signal).collect(Collectors.joining("\n"));
        return """
                skills-gateway.oidc.issuer is not set, and this gateway has an identity provider configured:

                %s

                Without an expected issuer the login accepts an identity token from any issuer whose \
                keys are in the provider's key set. Where one authorization endpoint serves many \
                tenants, every tenant's tokens verify against those keys, so the issuer is the tenant \
                boundary and the gateway will not start without it.

                Resolve it one of two ways:
                  * a real deployment: set skills-gateway.oidc.issuer (environment variable \
                SKILLSGATEWAY_OIDC_ISSUER) to the "issuer" value your provider publishes at \
                /.well-known/openid-configuration;
                  * genuinely local development: leave the identity-provider configuration unset, \
                so the shipped placeholders (client id "%s", provider host "%s") stay in force.""".formatted(
                evidence, DevInsecureAuthGuard.PLACEHOLDER_CLIENT_ID, DevInsecureAuthGuard.PLACEHOLDER_PROVIDER_HOST);
    }
}
