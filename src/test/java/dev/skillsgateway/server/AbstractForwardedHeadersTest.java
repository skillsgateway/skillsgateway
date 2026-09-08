package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * How the gateway learns what the outside world sees when TLS terminates in front of it
 * (GW_AUTH_0029). Each subclass is one deployment posture, booted as a real server so the request
 * reaches Tomcat's valves and the servlet filter chain — the two places a forwarded header can be
 * honoured — rather than a MockMvc that has neither. One class per posture rather than
 * {@code @Nested} classes, because the traceability gate matches annotated tests to surefire
 * results by class name and a nested class is reported under a name the annotation does not carry.
 *
 * <p>The probe is the OIDC authorization redirect: its {@code redirect_uri} is {@code {baseUrl}}
 * expanded from the request, which is exactly the URL the issue behind this requirement saw arrive
 * at the identity provider with the wrong scheme.
 *
 * <p>Unlike the shared fixture, this context leaves {@code redirect-uri} to {@code
 * application.yaml}, so the {@code SGW_OIDC_REDIRECT_URI} placeholder it ships is what is under
 * test and not a copy of it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.data-dir=target/test-git-data",
            "skills-gateway.roles.admins=user",
            // Boot switches Tomcat's forwarded-header support on by itself when it detects a cloud
            // platform from the environment. Pinning "none" makes "unset" mean unset wherever this
            // runs, so the default-posture assertion is about the gateway, not about the CI host.
            "spring.main.cloud-platform=none"
        })
abstract class AbstractForwardedHeadersTest {

    static final String PUBLIC_HOST = "gateway.example.com";
    static final String LOGIN_PATH = "/login/oauth2/code/idp";

    @LocalServerPort
    int port;

    /** The identity provider's view: the {@code redirect_uri} the authorization request names. */
    String redirectUriSeenByTheIdp(Map<String, String> headers) {
        // Bound to the running server rather than to MockMvc: the whole point of this suite is that
        // the request reaches Tomcat's valves and the servlet filter chain. The request factory is
        // named rather than left to detection because the JDK client never follows a redirect, and
        // the redirect is the response under test -- a factory that followed it would turn every
        // assertion here into an assertion about the identity provider's 404 page.
        URI location = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri("/oauth2/authorization/idp")
                .headers(h -> headers.forEach(h::add))
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .returnResult()
                .getResponseHeaders()
                .getLocation();
        assertThat(location).isNotNull();
        String encoded =
                UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst("redirect_uri");
        assertThat(encoded).isNotNull();
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    static Map<String, String> forwardedByTheProxy() {
        return Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", PUBLIC_HOST);
    }
}
