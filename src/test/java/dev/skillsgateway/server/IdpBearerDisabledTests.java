package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.auth.IdpBearerAuthenticationProvider;
import dev.skillsgateway.server.auth.IdpBearerConfiguration;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The default, and the refusal that guards enabling it badly (GW_AUTH_0040).
 *
 * <p>This suite runs in the shared context on purpose: that context sets nothing about identity-
 * provider bearer tokens, which is what "off by default" means. The claim is stronger than "the
 * token is rejected" — it is that the capability is not installed at all, so there is no code on
 * the request path to be got wrong.
 */
class IdpBearerDisabledTests extends AbstractGatewayTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired(required = false)
    private IdpBearerAuthenticationProvider provider;

    @Autowired
    private SkillsGatewayProperties properties;

    @Test
    @SVCs({"SVC_GW_AUTH_0040"})
    void a_default_gateway_installs_nothing_and_refuses_a_perfectly_valid_token() throws Exception {
        assertThat(properties.facade().idpBearer().enabled())
                .as("the capability is off unless an operator typed it")
                .isFalse();
        assertThat(provider)
                .as("with the capability off there is no provider in the context at all")
                .isNull();

        String name = uniqueName("disabled");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(facadeUrl(name, null) + "/info/refs?service=git-upload-pack"))
                        .header("Authorization", "Bearer " + IdpBearerFixture.validToken("sso-alice"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        // The refusal is the ordinary one, so a client that presented an SSO token to a gateway
        // that does not take them is told to use the credential helper rather than left guessing.
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic");
    }

    /**
     * Enabling the capability without pinning an issuer refuses, and refuses at wiring time rather
     * than at request time. The signature check alone cannot separate one tenant of a shared
     * authorization endpoint from another, so a gateway that accepted tokens on that evidence would
     * be serving every organisation the provider hosts — and would look perfectly healthy doing it.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0040"})
    void enabling_the_capability_without_a_pinned_issuer_is_refused() {
        SkillsGatewayProperties unpinned = new SkillsGatewayProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new SkillsGatewayProperties.Oidc(null),
                new SkillsGatewayProperties.Facade(new SkillsGatewayProperties.IdpBearer(true, null)),
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> new IdpBearerConfiguration().idpBearerJwtDecoder(unpinned, registrations()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.oidc.issuer")
                .hasMessageContaining("skills-gateway.facade.idp-bearer.enabled");
    }

    private static ClientRegistrationRepository registrations() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("idp")
                .clientId("test")
                .clientSecret("test")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/idp")
                .authorizationUri("https://idp.invalid/authorize")
                .tokenUri("https://idp.invalid/token")
                .jwkSetUri("https://idp.invalid/jwks")
                .build();
        return registrationId -> "idp".equals(registrationId) ? registration : null;
    }
}
