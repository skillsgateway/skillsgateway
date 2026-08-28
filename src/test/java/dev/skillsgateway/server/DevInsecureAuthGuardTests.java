package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.auth.DevInsecureAuthGuard;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The fail-fast guard on the development escape hatch (GW_0110), exercised as a real Spring context
 * that either starts or refuses to. Each case boots the guard alongside Boot's own OAuth2 client
 * auto-configuration, so the client registrations the guard inspects are the ones Boot builds from
 * configuration rather than a hand-made stand-in.
 */
class DevInsecureAuthGuardTests {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir"));

    /** The unconfigured provider {@code application.yaml} ships, as an operator would inherit it. */
    private static final String[] PLACEHOLDER_IDP = {
        "spring.security.oauth2.client.registration.idp.client-id=change-me",
        "spring.security.oauth2.client.registration.idp.client-secret=change-me",
        "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/idp",
        "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
        "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks"
    };

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(GuardUnderTest.class)
            .withPropertyValues(PLACEHOLDER_IDP);

    @Test
    @SVCs({"SVC_GW_0110"})
    void the_escape_hatch_refuses_to_start_where_an_identity_provider_is_configured() {
        // The local development loop: the escape hatch on, no identity provider configured.
        contexts.withPropertyValues("skills-gateway.dev-insecure-auth=true").run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(DevInsecureAuthGuard.class)
                // The registrations the later cases are judged on are really present.
                .hasSingleBean(ClientRegistrationRepository.class));

        // A real client id is a configured provider, whatever the endpoints still say.
        contexts.withPropertyValues(
                        "skills-gateway.dev-insecure-auth=true",
                        "spring.security.oauth2.client.registration.idp.client-id=skills-gateway")
                .run(context -> assertRefused(context, "carries a real client id"));

        // So is an endpoint that has moved off the placeholder host.
        contexts.withPropertyValues(
                        "skills-gateway.dev-insecure-auth=true",
                        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.example.com/jwks")
                .run(context -> assertRefused(context, "real jwk-set-uri"));

        // And so is a pinned issuer, which only a deployment with a real provider can state.
        contexts.withPropertyValues(
                        "skills-gateway.dev-insecure-auth=true",
                        "skills-gateway.oidc.issuer=https://idp.example.com/tenant-a/v2.0")
                .run(context -> assertRefused(context, "skills-gateway.oidc.issuer is pinned"));

        // The guard only ever looks at deployments that opened the hatch: a fully configured
        // identity provider with the flag off — every real deployment — starts untouched.
        contexts.withPropertyValues(
                        "spring.security.oauth2.client.registration.idp.client-id=skills-gateway",
                        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.example.com/jwks",
                        "skills-gateway.oidc.issuer=https://idp.example.com/tenant-a/v2.0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The placeholders the guard treats as "no identity provider" are the ones the application
     * actually ships. Were application.yaml to change one, the guard would start reading a real
     * default as a placeholder and stop refusing anything.
     */
    @Test
    @SVCs({"SVC_GW_0110"})
    void the_placeholders_the_guard_trusts_are_the_ones_the_application_ships() throws IOException {
        String applicationYaml = Files.readString(REPO_ROOT.resolve("src/main/resources/application.yaml"));
        assertThat(applicationYaml)
                .contains("${SGW_OIDC_CLIENT_ID:" + DevInsecureAuthGuard.PLACEHOLDER_CLIENT_ID + "}")
                .contains("${SGW_OIDC_AUTHORIZATION_URI:https://" + DevInsecureAuthGuard.PLACEHOLDER_PROVIDER_HOST)
                .contains("${SGW_OIDC_TOKEN_URI:https://" + DevInsecureAuthGuard.PLACEHOLDER_PROVIDER_HOST)
                .contains("${SGW_OIDC_JWK_SET_URI:https://" + DevInsecureAuthGuard.PLACEHOLDER_PROVIDER_HOST);
    }

    /** The refusal must name what tripped it and both ways out; a bare failure teaches nobody. */
    private static void assertRefused(AssertableApplicationContext context, String signal) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.dev-insecure-auth=true refuses to start")
                .hasMessageContaining(signal)
                .hasMessageContaining("remove skills-gateway.dev-insecure-auth")
                .hasMessageContaining("leave the identity-provider configuration unset");
    }

    /** The guard itself, not a stand-in: imported as the component the application scans. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    @Import(DevInsecureAuthGuard.class)
    static class GuardUnderTest {}
}
