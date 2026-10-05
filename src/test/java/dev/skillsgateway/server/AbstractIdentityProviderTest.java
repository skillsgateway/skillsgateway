package dev.skillsgateway.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The shared fixtures pointed at a working identity provider, {@link IdpBearerFixture}, instead of
 * the unreachable one the shared context names. One context for every suite that needs a provider
 * to answer: the facade's bearer path (GW_AUTH_0040) and a browser sign-in that completes
 * (GW_AUTH_0053).
 */
@TestPropertySource(properties = "skills-gateway.facade.idp-bearer.enabled=true")
abstract class AbstractIdentityProviderTest extends AbstractGatewayTest {

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.idp.jwk-set-uri", IdpBearerFixture::jwkSetUri);
        registry.add("spring.security.oauth2.client.provider.idp.token-uri", IdpBearerFixture::tokenUri);
        registry.add("skills-gateway.oidc.issuer", () -> IdpBearerFixture.ISSUER);
    }
}
