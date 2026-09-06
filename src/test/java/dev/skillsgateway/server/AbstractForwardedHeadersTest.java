package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * How the gateway learns what the outside world sees when TLS terminates in front of it
 * (GW_0163). Each subclass is one deployment posture, booted as a real server so the request
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
        // The JDK client never follows a redirect, which is the response under test.
        ResponseEntity<Void> response = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .build()
                .get()
                .uri("http://localhost:" + port + "/oauth2/authorization/idp")
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .toBodilessEntity();
        assertThat(response.getStatusCode().is3xxRedirection()).isTrue();
        URI location = response.getHeaders().getLocation();
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
