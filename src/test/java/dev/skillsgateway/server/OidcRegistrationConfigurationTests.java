package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;

/**
 * The two knobs an enterprise identity provider needs the gateway to have (GW_0100), asserted
 * against the shipped {@code application.yaml} rather than against Spring's binder: the test sets
 * the environment variables an operator would set, and reads back the registration the application
 * actually built.
 */
@TestPropertySource(
        properties = {
            "SGW_OIDC_USER_NAME_ATTRIBUTE=preferred_username",
            "SGW_OIDC_SCOPE=openid,profile,email",
            "skills-gateway.oidc.issuer=https://idp.example.com/tenant-a/v2.0"
        })
class OidcRegistrationConfigurationTests extends AbstractGatewayTest {

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    @SVCs({"SVC_GW_0100"})
    void the_principal_claim_and_the_requested_scopes_come_from_configuration() {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId("idp");

        assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                .isEqualTo("preferred_username");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }
}
