package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.auth.DevInsecureAuthGuard;
import dev.skillsgateway.server.auth.IdTokenDecoderConfiguration;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * The login refuses to start without an expected issuer once an identity provider is configured
 * (GW_AUTH_0017), exercised as a real context that either starts or refuses to. Boot's own OAuth2
 * client auto-configuration builds the registrations from properties, starting from the
 * placeholders {@code application.yaml} ships, so "configured" means what it means in a running
 * gateway.
 */
class OidcIssuerRequiredTests {

    /** The unconfigured provider {@code application.yaml} ships, as an operator would inherit it. */
    private static final String[] PLACEHOLDER_IDP = {
        "spring.security.oauth2.client.registration.idp.client-id=change-me",
        "spring.security.oauth2.client.registration.idp.client-secret=change-me",
        "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/idp",
        "spring.security.oauth2.client.registration.idp.scope=openid",
        "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
        "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks"
    };

    private static final String REAL_CLIENT_ID = "spring.security.oauth2.client.registration.idp.client-id=gateway";

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(DecoderUnderTest.class)
            .withPropertyValues(PLACEHOLDER_IDP);

    @Test
    @SVCs({"SVC_GW_AUTH_0017.2"})
    void a_configured_provider_without_an_expected_issuer_refuses_to_start() {
        // A real client id is a configured provider, whatever the endpoints still say.
        contexts.withPropertyValues(REAL_CLIENT_ID)
                .run(context -> assertRefused(context, "the OIDC client registration 'idp' carries a real client id"));

        // So is an endpoint that has moved off the placeholder host, with the placeholder client id.
        contexts.withPropertyValues(
                        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.example.com/jwks")
                .run(context -> assertRefused(context, "has a real jwk-set-uri (https://idp.example.com/jwks)"));

        // An issuer of only whitespace pins nothing, so it is unset. Set through a map rather than
        // "key=value", which would trim it to empty and test a different case.
        contexts.withPropertyValues(REAL_CLIENT_ID)
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "whitespace-issuer", Map.of("skills-gateway.oidc.issuer", "   "))))
                .run(context -> assertRefused(context, "carries a real client id"));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0017.2"})
    void a_configured_provider_with_an_expected_issuer_starts_and_its_login_compares_it() {
        contexts.withPropertyValues(
                        "spring.security.oauth2.client.registration.idp.client-id=" + IdpBearerFixture.AUDIENCE,
                        "spring.security.oauth2.client.provider.idp.jwk-set-uri=" + IdpBearerFixture.jwkSetUri(),
                        "skills-gateway.oidc.issuer=" + IdpBearerFixture.ISSUER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtDecoder decoder = loginDecoder(context);

                    // The pinned issuer reaches the decoder the login uses: a token from it
                    // verifies, and one from another tenant signed by the same keys does not.
                    assertThat(decoder.decode(IdpBearerFixture.validToken("alice"))
                                    .getIssuer()
                                    .toString())
                            .isEqualTo(IdpBearerFixture.ISSUER);
                    assertThatThrownBy(() -> decoder.decode(IdpBearerFixture.token(
                                    "alice", claims -> claims.issuer(IdpBearerFixture.FOREIGN_ISSUER))))
                            .isInstanceOf(JwtValidationException.class)
                            .hasMessageContaining("iss");
                });
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0017.2"})
    void the_unconfigured_state_and_the_escape_hatch_start_without_an_issuer() {
        // The shipped placeholders: nothing can log in, so there is nothing to pin yet. A bare
        // local run must keep starting.
        contexts.run(context -> assertThat(context).hasNotFailed());

        // The development escape hatch with the placeholders in force: the documented local loop.
        contexts.withPropertyValues("skills-gateway.dev-insecure-auth=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Under the escape hatch a configured provider is the hatch guard's refusal to make, and its
     * message is the one that matters — the hatch opens the whole web surface. This check stands
     * aside there rather than risk being the message an operator reads instead.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0017.2"})
    void under_the_escape_hatch_a_configured_provider_is_refused_by_the_hatch_guard() {
        contexts.withPropertyValues("skills-gateway.dev-insecure-auth=true", REAL_CLIENT_ID)
                .run(context -> assertThat(context).hasNotFailed());

        contexts.withUserConfiguration(HatchGuard.class)
                .withPropertyValues("skills-gateway.dev-insecure-auth=true", REAL_CLIENT_ID)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("skills-gateway.dev-insecure-auth=true refuses to start")
                            .hasMessageNotContaining("skills-gateway.oidc.issuer is not set");
                });
    }

    /** The refusal must name the property, what showed a provider to be configured, and the ways out. */
    private static void assertRefused(AssertableApplicationContext context, String signal) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.oidc.issuer is not set")
                .hasMessageContaining(signal)
                .hasMessageContaining("SKILLSGATEWAY_OIDC_ISSUER")
                .hasMessageContaining("leave the identity-provider configuration unset");
    }

    private static JwtDecoder loginDecoder(AssertableApplicationContext context) {
        @SuppressWarnings("unchecked")
        JwtDecoderFactory<ClientRegistration> factory = (JwtDecoderFactory<ClientRegistration>) context.getBeanProvider(
                        ResolvableType.forClassWithGenerics(JwtDecoderFactory.class, ClientRegistration.class))
                .getObject();
        ClientRegistration registration =
                context.getBean(ClientRegistrationRepository.class).findByRegistrationId("idp");
        return factory.createDecoder(registration);
    }

    /** The decoder configuration itself, not a stand-in. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    @Import(IdTokenDecoderConfiguration.class)
    static class DecoderUnderTest {}

    @Configuration(proxyBeanMethods = false)
    @Import(DevInsecureAuthGuard.class)
    static class HatchGuard {}
}
