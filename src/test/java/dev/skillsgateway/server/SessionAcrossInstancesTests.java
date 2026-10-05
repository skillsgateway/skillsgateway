package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two gateways on one database, and a load balancer that sends each request to whichever it likes
 * (GW_AUTH_0053). The test context is the first instance; the second is started here with the
 * same configuration and the first one's database, which is what a second replica is.
 */
class SessionAcrossInstancesTests extends AbstractIdentityProviderTest {

    private static ConfigurableApplicationContext second;

    @Autowired
    private JdbcConnectionDetails database;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void startTheSecondInstance() {
        if (second != null) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String pair :
                GatewayContext.class.getAnnotation(SpringBootTest.class).properties()) {
            String[] parts = pair.split("=", 2);
            properties.put(parts[0], parts[1]);
        }
        properties.put("skills-gateway.facade.idp-bearer.enabled", "true");
        properties.put("spring.security.oauth2.client.provider.idp.jwk-set-uri", IdpBearerFixture.jwkSetUri());
        properties.put("spring.security.oauth2.client.provider.idp.token-uri", IdpBearerFixture.tokenUri());
        properties.put("skills-gateway.oidc.issuer", IdpBearerFixture.ISSUER);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("arconia.dev.services.postgresql.enabled", "false");
        properties.put("arconia.dev.services.floci.enabled", "false");
        properties.put("server.port", "0");
        properties.put("spring.main.banner-mode", "off");
        // The filesystem backend needs no S3 client, and without the object-store dev service there
        // is no region to build one from.
        properties.put("spring.cloud.aws.s3.enabled", "false");
        // Arguments rather than default properties, which application.yaml would override.
        second = new SpringApplicationBuilder(SkillsGatewayApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(properties.entrySet().stream()
                        .map(property -> "--" + property.getKey() + "=" + property.getValue())
                        .toArray(String[]::new));
    }

    @AfterAll
    static void stopTheSecondInstance() {
        if (second != null) {
            second.close();
            second = null;
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0053"})
    void a_sign_in_started_on_one_instance_completes_on_the_other_and_both_honour_the_session() throws Exception {
        String first = "http://localhost:" + port;
        String other = "http://localhost:"
                + ((WebServerApplicationContext) second).getWebServer().getPort();
        Map<String, String> jar = new LinkedHashMap<>();

        // The sign-in starts on the first instance, which saves the authorization request.
        HttpResponse<String> started = get(first + "/oauth2/authorization/idp", jar);
        assertThat(started.statusCode()).isEqualTo(302);
        Map<String, String> query =
                query(started.headers().firstValue("location").orElseThrow());

        // The provider's redirect back lands on the other instance. The code is the nonce, which is
        // how the stand-in provider knows what to put in the ID token.
        HttpResponse<String> callback = get(
                other + "/login/oauth2/code/idp?code=" + encode(query.get("nonce")) + "&state="
                        + encode(query.get("state")),
                jar);
        assertThat(callback.statusCode())
                .as("the other instance found the sign-in this instance started: %s", callback.body())
                .isEqualTo(302);

        for (String instance : List.of(first, other)) {
            HttpResponse<String> me = get(instance + "/api/v1/me", jar);
            assertThat(me.statusCode())
                    .as("the session is honoured on %s", instance)
                    .isEqualTo(200);
            assertThat(me.body()).contains("\"user\"");
        }
    }

    /**
     * What an upgrade can leave behind: a session whose stored state the running version cannot
     * read. Its holder signs in again; nobody gets a 500 until it expires.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0053"})
    void a_session_whose_stored_state_cannot_be_read_is_no_session() throws Exception {
        String primaryId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.sql("""
                        INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
                                                    max_inactive_interval, expiry_time, principal_name)
                        VALUES (:primary, :session, :now, :now, 1800, :expiry, 'user')""")
                .param("primary", primaryId)
                .param("session", sessionId)
                .param("now", now)
                .param("expiry", now + 1_800_000)
                .update();
        jdbc.sql("""
                        INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                        VALUES (:primary, 'SPRING_SECURITY_CONTEXT', :bytes)""")
                .param("primary", primaryId)
                .param("bytes", "written by some other version".getBytes(StandardCharsets.UTF_8))
                .update();
        Map<String, String> jar = new LinkedHashMap<>();
        jar.put("SESSION", Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));

        HttpResponse<String> me = get("http://localhost:" + port + "/api/v1/me", jar);

        assertThat(me.statusCode())
                .as("an unreadable session is answered as no session: %s", me.body())
                .isEqualTo(401);
    }

    /** One request, with the jar's cookies sent and whatever the response sets kept. */
    private static HttpResponse<String> get(String url, Map<String, String> jar) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (!jar.isEmpty()) {
            request.header(
                    "Cookie",
                    jar.entrySet().stream()
                            .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                            .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        List<String> set = new ArrayList<>(response.headers().allValues("set-cookie"));
        for (String cookie : set) {
            String[] pair = cookie.split(";", 2)[0].split("=", 2);
            if (pair.length == 2 && !pair[1].isEmpty()) {
                jar.put(pair[0], pair[1]);
            } else {
                jar.remove(pair[0]);
            }
        }
        return response;
    }

    private static Map<String, String> query(String location) {
        return Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> pair[0],
                        pair -> URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8),
                        (a, b) -> a));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
