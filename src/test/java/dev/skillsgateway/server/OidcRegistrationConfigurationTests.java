package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The two knobs an enterprise identity provider needs the gateway to have (GW_AUTH_0017), asserted
 * against the shipped {@code application.yaml} rather than against Spring's binder: the test sets
 * the environment variables an operator would set, and reads back the registration the application
 * actually built.
 *
 * <p>The context is an {@link ApplicationContextRunner} rather than a {@code @SpringBootTest}, and
 * the substitution is exact: {@link ConfigDataApplicationContextInitializer} loads the same shipped
 * {@code application.yaml}, and {@link OAuth2ClientAutoConfiguration} is the same auto-configuration
 * that turns those values into a registration in a running gateway. Nothing else in the application
 * participates in building one, so nothing else needs to start — and the runner's context is closed
 * per test rather than cached, which is what takes it off the suite's context budget (#305).
 */
class OidcRegistrationConfigurationTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class));

    @Test
    @SVCs({"SVC_GW_AUTH_0017"})
    void the_principal_claim_and_the_requested_scopes_come_from_configuration() {
        contexts.withPropertyValues(
                        "SGW_OIDC_USER_NAME_ATTRIBUTE=preferred_username",
                        "SGW_OIDC_SCOPE=openid,profile,email",
                        "skills-gateway.oidc.issuer=https://idp.example.com/tenant-a/v2.0")
                .run(context -> {
                    ClientRegistration registration =
                            context.getBean(ClientRegistrationRepository.class).findByRegistrationId("idp");

                    assertThat(registration
                                    .getProviderDetails()
                                    .getUserInfoEndpoint()
                                    .getUserNameAttributeName())
                            .isEqualTo("preferred_username");
                    assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
                });
    }
}
