package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.UpstreamCredential;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Upstream credentials away from any Spring context (GW_INGEST_0050 to GW_INGEST_0053): which
 * credential a URL selects, which requests it may ride on, and which entries refuse to load. Every
 * token here is made up.
 */
class UpstreamCredentialsTests {

    private static final String ACME_TOKEN = "tok-acme-4f1c9e";
    private static final String HOST_TOKEN = "tok-host-77ab02";

    private HttpServer server;
    private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationByPath.put(exchange.getRequestURI().getPath(), header == null ? "" : header);
            if (exchange.getRequestURI().getPath().endsWith("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/elsewhere/landed");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static UpstreamCredentials credentials(UpstreamCredential... entries) {
        return new UpstreamCredentials(List.of(entries));
    }

    private static UpstreamCredential entry(String prefix, String token) {
        return new UpstreamCredential(prefix, "sgw", token, null);
    }

    private static URL url(String spec) throws Exception {
        return URI.create(spec).toURL();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0050"})
    void a_prefix_matches_whole_path_segments_only() {
        UpstreamCredentials credentials = credentials(entry("https://forge.example/acme", ACME_TOKEN));

        assertThat(credentials.select("https://forge.example/acme/skills.git")).isPresent();
        assertThat(credentials.select("https://FORGE.example:443/acme/x")).isPresent();
        assertThat(credentials.select("HTTPS://forge.example/acme")).isPresent();
        assertThat(credentials.select("https://forge.example/acme-evil/x.git")).isEmpty();
        assertThat(credentials.select("https://forge.example/acmex")).isEmpty();
        assertThat(credentials.select("http://forge.example/acme/x")).isEmpty();
        assertThat(credentials.select("https://forge.example:8443/acme/x")).isEmpty();
        assertThat(credentials.select("https://forge.example/")).isEmpty();
        assertThat(credentials.select("https://forge.example.evil/acme/x")).isEmpty();
        assertThat(credentials.select("/srv/git/acme/x")).isEmpty();
        assertThat(credentials.select("not a url")).isEmpty();
        assertThat(credentials.select(null)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0050"})
    void a_trailing_slash_on_the_prefix_means_nothing() {
        UpstreamCredentials credentials = credentials(entry("https://forge.example/acme/", ACME_TOKEN));

        assertThat(credentials.select("https://forge.example/acme/x.git")).isPresent();
        assertThat(credentials.select("https://forge.example/acme-evil/x.git")).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0050"})
    void the_longest_prefix_wins_whatever_the_order() {
        for (List<UpstreamCredential> order : List.of(
                List.of(entry("https://forge.example/", HOST_TOKEN), entry("https://forge.example/acme/", ACME_TOKEN)),
                List.of(
                        entry("https://forge.example/acme/", ACME_TOKEN),
                        entry("https://forge.example", HOST_TOKEN)))) {
            UpstreamCredentials credentials = new UpstreamCredentials(order);

            assertThat(credentials.select("https://forge.example/acme/x.git"))
                    .get()
                    .extracting(UpstreamCredentials.Selected::urlPrefix)
                    .isEqualTo("https://forge.example/acme/");
            assertThat(credentials.select("https://forge.example/other/x.git"))
                    .get()
                    .extracting(UpstreamCredentials.Selected::urlPrefix)
                    .asString()
                    .startsWith("https://forge.example");
            assertThat(credentials
                            .select("https://forge.example/other/x.git")
                            .orElseThrow()
                            .urlPrefix())
                    .doesNotContain("acme");
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void a_non_canonical_request_url_is_never_covered() throws Exception {
        UpstreamCredentials.Selected selected = credentials(entry("https://forge.example/acme", ACME_TOKEN))
                .select("https://forge.example/acme/x.git")
                .orElseThrow();

        assertThat(selected.covers(url("https://forge.example/acme/x.git/info/refs?service=git-upload-pack")))
                .isTrue();
        assertThat(selected.covers(url("https://forge.example/acme/../evil/x.git")))
                .isFalse();
        assertThat(selected.covers(url("https://forge.example/acme/./x.git"))).isFalse();
        assertThat(selected.covers(url("https://forge.example/acme/%2e%2e/evil/x.git")))
                .isFalse();
        assertThat(selected.covers(url("https://forge.example/acme/%2E%2E/evil/x.git")))
                .isFalse();
        assertThat(selected.covers(url("https://forge.example/acme%2fevil/x.git")))
                .isFalse();
        assertThat(selected.covers(url("https://forge.example/acme/x%5c..%5cevil")))
                .isFalse();
        assertThat(selected.covers(url("https://u@forge.example/acme/x.git"))).isFalse();
        assertThat(selected.covers(url("https://forge.example/other/x.git"))).isFalse();
        assertThat(selected.covers(url("https://forge.example:444/acme/x.git"))).isFalse();
        assertThat(selected.covers(url("http://forge.example/acme/x.git"))).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void a_url_with_dot_segments_or_userinfo_selects_nothing() {
        UpstreamCredentials credentials = credentials(entry("https://forge.example/acme", ACME_TOKEN));

        assertThat(credentials.select("https://forge.example/acme/../evil/x.git"))
                .isEmpty();
        assertThat(credentials.select("https://forge.example/acme/%2e%2e/evil/x.git"))
                .isEmpty();
        assertThat(credentials.select("https://u:p@forge.example/acme/x.git")).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void the_connection_factory_attaches_the_credential_only_under_the_prefix() throws Exception {
        UpstreamCredentials.Selected selected = credentials(entry(base() + "/acme/", ACME_TOKEN))
                .select(base() + "/acme/x.git")
                .orElseThrow();
        String expected =
                "Basic " + Base64.getEncoder().encodeToString(("sgw:" + ACME_TOKEN).getBytes(StandardCharsets.UTF_8));

        HttpConnection inside = selected.connectionFactory().create(url(base() + "/acme/x.git/info/refs?service=x"));
        inside.getResponseCode();
        HttpConnection outside = selected.connectionFactory().create(url(base() + "/other/x.git/info/refs"));
        outside.setRequestProperty("Authorization", "Basic c2V0LWJ5LWpnaXQ=");
        outside.getResponseCode();

        assertThat(authorizationByPath).containsEntry("/acme/x.git/info/refs", expected);
        assertThat(authorizationByPath).containsEntry("/other/x.git/info/refs", "");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void the_jdk_is_never_allowed_to_follow_a_redirect_with_the_credential() throws Exception {
        UpstreamCredentials.Selected selected = credentials(entry(base() + "/acme/", ACME_TOKEN))
                .select(base() + "/acme/x.git")
                .orElseThrow();

        HttpConnection connection = selected.connectionFactory().create(url(base() + "/acme/redirect"));
        connection.setInstanceFollowRedirects(true);

        assertThat(connection.getResponseCode()).isEqualTo(302);
        assertThat(authorizationByPath).doesNotContainKey("/elsewhere/landed");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0053"})
    void an_unusable_entry_is_refused_naming_the_entry_and_never_the_token() {
        String secret = "tok-never-printed-91d2";
        List<UpstreamCredential> refused = List.of(
                new UpstreamCredential("https://forge.example/acme", "sgw", "${SGW_MISSING}", null),
                new UpstreamCredential("https://forge.example/acme", "${SGW_USER}", secret, null),
                new UpstreamCredential("https://forge.example/acme", "sgw", " ", null),
                new UpstreamCredential("https://forge.example/acme", "sgw", null, null),
                new UpstreamCredential("https://forge.example/acme", "", secret, null),
                new UpstreamCredential("http://forge.example/acme", "sgw", secret, null),
                new UpstreamCredential("http://10.0.0.5/acme", "sgw", secret, null),
                new UpstreamCredential("ssh://forge.example/acme", "sgw", secret, null),
                new UpstreamCredential("file:///srv/git/", "sgw", secret, null),
                new UpstreamCredential("https://u:p@forge.example/acme", "sgw", secret, null),
                new UpstreamCredential("https://forge.example/acme?x=1", "sgw", secret, null),
                new UpstreamCredential("https://forge.example/acme#f", "sgw", secret, null),
                new UpstreamCredential("https://forge.example/acme/../evil", "sgw", secret, null),
                new UpstreamCredential("https://forge.example/%2e%2e/evil", "sgw", secret, null),
                new UpstreamCredential("forge.example/acme", "sgw", secret, null),
                new UpstreamCredential(null, "sgw", secret, null),
                new UpstreamCredential("not a url at all", "sgw", secret, null));

        for (UpstreamCredential bad : refused) {
            assertThatThrownBy(() -> credentials(entry("https://ok.example/", HOST_TOKEN), bad))
                    .as(bad.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("skills-gateway.ingestion.upstream-credentials[1]")
                    .hasMessageNotContaining(secret)
                    .hasMessageNotContaining(HOST_TOKEN);
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0053"})
    void two_entries_for_one_prefix_are_refused() {
        assertThatThrownBy(() -> credentials(
                        entry("https://forge.example/acme", ACME_TOKEN),
                        entry("https://FORGE.example:443/acme/", HOST_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream-credentials[1]")
                .hasMessageContaining("upstream-credentials[0]")
                .hasMessageNotContaining(ACME_TOKEN)
                .hasMessageNotContaining(HOST_TOKEN);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0053"})
    void https_and_loopback_http_entries_load() {
        UpstreamCredentials credentials = credentials(
                entry("https://forge.example/acme", ACME_TOKEN),
                entry("http://127.0.0.1:8080/", HOST_TOKEN),
                entry("http://localhost/team/", HOST_TOKEN),
                entry("http://[::1]:3000/", HOST_TOKEN));

        assertThat(credentials.select("http://127.0.0.1:8080/x/y.git")).isPresent();
        assertThat(credentials.select("http://localhost:80/team/y.git")).isPresent();
        assertThat(credentials.select("http://[::1]:3000/y.git")).isPresent();
        assertThat(credentials.secrets()).containsExactlyInAnyOrder(ACME_TOKEN, HOST_TOKEN, HOST_TOKEN, HOST_TOKEN);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0053"})
    void no_entries_is_the_anonymous_gateway() {
        UpstreamCredentials credentials = new UpstreamCredentials(new SkillsGatewayProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

        assertThat(credentials.select("https://forge.example/acme/x.git")).isEmpty();
        assertThat(credentials.secrets()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0052"})
    void the_properties_never_print_the_token() {
        UpstreamCredential credential = entry("https://forge.example/acme", ACME_TOKEN);
        SkillsGatewayProperties.Ingestion ingestion = new SkillsGatewayProperties.Ingestion(null, List.of(credential));

        assertThat(credential.toString()).doesNotContain(ACME_TOKEN).contains("https://forge.example/acme");
        assertThat(ingestion.toString()).doesNotContain(ACME_TOKEN);
        assertThat(new SkillsGatewayProperties(
                                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                                null, null, ingestion, null)
                        .toString())
                .doesNotContain(ACME_TOKEN);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0052"})
    void a_token_a_server_quotes_is_scrubbed_from_the_failure() {
        UpstreamFailure quoted = UpstreamFailure.of(new IOException("server said: bad credential " + ACME_TOKEN));

        UpstreamFailure scrubbed = new UpstreamFailure("reason " + ACME_TOKEN, "next " + ACME_TOKEN, quoted.rootCause())
                .scrub(List.of(ACME_TOKEN, HOST_TOKEN));

        assertThat(scrubbed.reason()).doesNotContain(ACME_TOKEN);
        assertThat(scrubbed.nextStep()).doesNotContain(ACME_TOKEN);
        assertThat(scrubbed.rootCause()).doesNotContain(ACME_TOKEN).contains("server said: bad credential ***");
        assertThat(scrubbed.describe()).doesNotContain(ACME_TOKEN);
        assertThat(quoted.scrub(List.of())).isEqualTo(quoted);
    }
}
