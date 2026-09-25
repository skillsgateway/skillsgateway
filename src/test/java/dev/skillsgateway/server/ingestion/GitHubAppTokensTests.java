package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.TestKeys;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.GitHubApp;
import dev.skillsgateway.server.config.SkillsGatewayProperties.UpstreamCredential;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitHub App credentials away from any Spring context (GW_INGEST_0056 to GW_INGEST_0061,
 * GW_AUTH_0049, GW_AUTH_0050): which keys and entries load, what the assertion says, how the API's
 * refusals read, and how long a token is reused. The key is generated here; no key is committed.
 */
class GitHubAppTokensTests {

    private static final String PREFIX = "https://github.com/acme/";

    private static KeyPair rsa;
    private static String pkcs1;
    private static String pkcs8;

    private HttpServer api;
    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger minted = new AtomicInteger();
    private final AtomicReference<Duration> lifetime = new AtomicReference<>(Duration.ofHours(1));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"));

    @BeforeAll
    static void generateKey() throws Exception {
        rsa = TestKeys.rsa();
        pkcs8 = TestKeys.pkcs8Pem(rsa);
        pkcs1 = TestKeys.pkcs1Pem(rsa);
    }

    @BeforeEach
    void startApi() throws IOException {
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/app/installations/77/access_tokens", exchange -> {
            tokenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "{\"token\":\"ghs_unit_%d\",\"expires_at\":\"%s\"}"
                    .formatted(minted.incrementAndGet(), clock.instant().plus(lifetime.get()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api.start();
    }

    @AfterEach
    void stopApi() {
        api.stop(0);
    }

    private String apiUrl() {
        return "http://127.0.0.1:" + api.getAddress().getPort();
    }

    private static UpstreamCredential app(String appId, String key, String installation, String apiUrl) {
        return new UpstreamCredential(PREFIX, null, null, new GitHubApp(appId, key, installation, apiUrl));
    }

    private UpstreamCredentials load(UpstreamCredential... entries) {
        return new UpstreamCredentials(List.of(entries), clock);
    }

    private GitHubAppTokens tokens(String installation) {
        return GitHubAppTokens.load("entry", new GitHubApp("4242", pkcs1, installation, apiUrl()), clock);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0060"})
    void github_issues_pkcs1_and_both_forms_load() {
        for (String key : List.of(pkcs1, pkcs8, "\n  " + pkcs1 + "  \n", pkcs1.replace("\n", "\\n"))) {
            RSAPrivateCrtKey loaded = (RSAPrivateCrtKey) GitHubAppTokens.readKey(key);
            RSAPrivateCrtKey expected = (RSAPrivateCrtKey) rsa.getPrivate();
            assertThat(loaded.getModulus()).isEqualTo(expected.getModulus());
            assertThat(loaded.getPrivateExponent()).isEqualTo(expected.getPrivateExponent());
        }
        assertThat(load(app("4242", pkcs1, null, null)).select(PREFIX + "skills.git"))
                .hasValueSatisfying(
                        selected -> assertThat(selected.isGitHubApp()).isTrue());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0060", "SVC_GW_AUTH_0049"})
    void an_unreadable_key_stops_startup_and_is_never_quoted() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String body = pkcs1.lines().filter(line -> !line.startsWith("-----")).reduce("", String::concat);
        List<String> bad = List.of(
                "-----BEGIN RSA PRIVATE KEY-----\n" + body.substring(0, body.length() / 2)
                        + "\n-----END RSA PRIVATE KEY-----",
                "-----BEGIN RSA PRIVATE KEY-----\n!!" + body + "\n-----END RSA PRIVATE KEY-----",
                TestKeys.pem("PRIVATE KEY", ec.generateKeyPair().getPrivate().getEncoded()),
                pkcs8.replace("PRIVATE KEY", "ENCRYPTED PRIVATE KEY"),
                pkcs8.replace("PRIVATE KEY", "CERTIFICATE"),
                "-----BEGIN RSA PRIVATE KEY-----\n" + body,
                "not a key",
                " ");
        for (String key : bad) {
            assertThatThrownBy(() -> load(app("4242", key, null, null)))
                    .as(key.length() > 40 ? key.substring(0, 40) : key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("upstream-credentials[0]")
                    .hasMessageContaining(PREFIX)
                    .satisfies(e -> {
                        for (String line : (key + "\n" + body).lines().toList()) {
                            if (line.length() >= 16 && !line.startsWith("-----")) {
                                assertThat(e.getMessage()).doesNotContain(line.substring(0, 16));
                            }
                        }
                    });
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0060"})
    void an_unusable_app_entry_stops_startup_naming_the_entry() {
        List<UpstreamCredential> bad = new ArrayList<>(List.of(
                new UpstreamCredential(PREFIX, "sgw", null, new GitHubApp("4242", pkcs1, null, null)),
                new UpstreamCredential(PREFIX, null, "tok", new GitHubApp("4242", pkcs1, null, null)),
                new UpstreamCredential(PREFIX, null, null, null),
                app(null, pkcs1, null, null),
                app(" ", pkcs1, null, null),
                app("abc", pkcs1, null, null),
                app("0", pkcs1, null, null),
                app("-5", pkcs1, null, null),
                app("${SGW_APP_ID}", pkcs1, null, null),
                app("4242", pkcs1, "x1", null),
                app("4242", "${SGW_KEY}", null, null),
                app("4242", null, null, null),
                app("4242", pkcs1, null, "http://api.example"),
                app("4242", pkcs1, null, "https://u@api.example"),
                app("4242", pkcs1, null, "https://api.example?x"),
                app("4242", pkcs1, null, "https://api.example#f"),
                app("4242", pkcs1, null, "ftp://api.example"),
                app("4242", pkcs1, null, "not a url"),
                app("4242", pkcs1, null, "${SGW_API}")));
        for (UpstreamCredential entry : bad) {
            assertThatThrownBy(() -> load(entry))
                    .as(entry.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("upstream-credentials[0]")
                    .hasMessageNotContaining(pkcs1.lines().toList().get(1));
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0060", "SVC_GW_INGEST_0061"})
    void a_usable_app_entry_loads_and_the_api_defaults_to_github() {
        assertThat(GitHubAppTokens.load("e", new GitHubApp("4242", pkcs1, null, null), clock)
                        .apiBase())
                .hasToString("https://api.github.com");
        assertThat(GitHubAppTokens.load("e", new GitHubApp("4242", pkcs1, "", "https://ghe.example/api/v3/"), clock)
                        .apiBase())
                .hasToString("https://ghe.example/api/v3");
        assertThat(GitHubAppTokens.load("e", new GitHubApp("4242", pkcs8, "77", "http://127.0.0.1:9"), clock)
                        .apiBase())
                .hasToString("http://127.0.0.1:9");
        assertThat(GitHubAppTokens.load("e", new GitHubApp("4242", pkcs8, "77", "http://localhost:9"), clock)
                        .apiBase())
                .hasToString("http://localhost:9");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0050"})
    void the_assertion_is_signed_by_the_app_key_and_short_lived() throws Exception {
        GitHubAppTokens tokens = tokens("77");
        Instant signedAt = clock.instant();

        String first = tokens.assertion();
        clock.advance(Duration.ofSeconds(1));
        String second = tokens.assertion();

        assertThat(first).isNotEqualTo(second);
        SignedJWT jwt = SignedJWT.parse(first);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) rsa.getPublic())))
                .isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("4242");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(signedAt.minusSeconds(60));
        assertThat(claims.getExpirationTime().toInstant()).isAfter(signedAt);
        assertThat(Duration.between(signedAt, claims.getExpirationTime().toInstant()))
                .isLessThanOrEqualTo(Duration.ofMinutes(10));
        assertThat(Duration.between(
                        claims.getIssueTime().toInstant(),
                        claims.getExpirationTime().toInstant()))
                .isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0059"})
    void a_refusal_from_the_api_reads_as_its_cause() {
        Instant now = clock.instant();
        String sameDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now, ZoneOffset.UTC));
        String skewedDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(now.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        GitHubAppTokens.Phase lookup = GitHubAppTokens.Phase.LOOKUP;
        GitHubAppTokens.Phase token = GitHubAppTokens.Phase.TOKEN;

        assertThat(GitHubAppTokens.classify(lookup, 404, "Not Found", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_NOT_INSTALLED);
        assertThat(GitHubAppTokens.classify(token, 422, "There is at least one repository", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_NOT_INSTALLED);
        assertThat(GitHubAppTokens.classify(token, 404, "Not Found", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_INSTALLATION_NOT_FOUND);
        assertThat(GitHubAppTokens.classify(token, 403, "This installation has been suspended", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_SUSPENDED);
        assertThat(GitHubAppTokens.classify(token, 403, "API rate limit exceeded", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_NO_TOKEN);
        assertThat(GitHubAppTokens.classify(
                                token,
                                401,
                                "'Issued at' claim ('iat') must be an Integer representing the time that the"
                                        + " assertion was issued.",
                                sameDate,
                                now,
                                "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_CLOCK_SKEW);
        assertThat(GitHubAppTokens.classify(
                                lookup,
                                401,
                                "'Expiration time' claim ('exp') is too far in the future",
                                sameDate,
                                now,
                                "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_CLOCK_SKEW);
        assertThat(GitHubAppTokens.classify(token, 401, "A JSON web token could not be decoded", skewedDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_CLOCK_SKEW);
        assertThat(GitHubAppTokens.classify(token, 401, "A JSON web token could not be decoded", sameDate, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_KEY_REFUSED);
        assertThat(GitHubAppTokens.classify(token, 401, "Bad credentials", null, now, "x")
                        .reason())
                .isEqualTo(UpstreamFailure.APP_KEY_REFUSED);
        assertThat(GitHubAppTokens.classify(token, 500, "boom", sameDate, now, "POST /x"))
                .satisfies(failure -> {
                    assertThat(failure.reason()).isEqualTo(UpstreamFailure.APP_NO_TOKEN);
                    assertThat(failure.rootCause())
                            .contains("500")
                            .contains("POST /x")
                            .contains("boom");
                });
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0061"})
    void only_a_plain_owner_and_repository_name_a_repository() {
        for (String url : List.of(
                "https://github.com/acme/skills.git",
                "https://github.com/acme/skills",
                "https://github.com/acme/skills/")) {
            assertThat(GitHubAppTokens.repository(url)).as(url).contains(new GitHubAppTokens.Repo("acme", "skills"));
        }
        assertThat(GitHubAppTokens.repository("https://ghe.example/Team.One/my_repo-2.git"))
                .contains(new GitHubAppTokens.Repo("Team.One", "my_repo-2"));
        for (String url : List.of(
                "https://github.com/acme",
                "https://github.com/acme/a/b",
                "https://github.com/ac%20me/x",
                "https://github.com/acme/.git",
                "https://github.com/acme/..",
                "https://github.com/acme/.",
                "https://github.com/acme/%3F",
                "https://github.com/acme/sk%2fills",
                "https://github.com/acme/x?y",
                "https://github.com//x",
                "/srv/git/acme/x",
                "not a url")) {
            assertThat(GitHubAppTokens.repository(url)).as(url).isEmpty();
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0049"})
    void the_properties_never_print_the_key() {
        GitHubApp app = new GitHubApp("4242", pkcs1, "77", "https://api.github.com");
        UpstreamCredential credential = new UpstreamCredential(PREFIX, null, null, app);
        SkillsGatewayProperties.Ingestion ingestion = new SkillsGatewayProperties.Ingestion(null, List.of(credential));
        String secretLine = pkcs1.lines().toList().get(1);

        for (Object printed : List.of(app, credential, ingestion)) {
            assertThat(printed.toString()).doesNotContain(secretLine).doesNotContain("BEGIN");
        }
        assertThat(app.toString()).contains("4242");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0058", "SVC_GW_INGEST_0057"})
    void a_token_is_reused_until_five_minutes_before_it_expires() {
        GitHubAppTokens tokens = tokens("77");
        String url = PREFIX + "skills.git";

        GitHubAppTokens.Minted first = tokens.token(url, false);
        assertThat(first.cached()).isFalse();
        clock.advance(Duration.ofMinutes(54).plusSeconds(59));
        GitHubAppTokens.Minted reused = tokens.token(url, false);
        assertThat(reused).isEqualTo(new GitHubAppTokens.Minted(first.token(), true));
        assertThat(tokens.secrets()).contains(first.token());

        clock.advance(Duration.ofSeconds(2));
        GitHubAppTokens.Minted renewed = tokens.token(url, false);
        assertThat(renewed.cached()).isFalse();
        assertThat(renewed.token()).isNotEqualTo(first.token());

        assertThat(tokens.token(url, true).token()).isNotEqualTo(renewed.token());
        tokens.forget(url);
        assertThat(tokens.token(url, false).cached()).isFalse();
        assertThat(tokens.token(PREFIX + "other.git", false).cached()).isFalse();
        assertThat(minted).hasValue(5);
        assertThat(tokenBodies).allSatisfy(body -> assertThat(body)
                .isIn(
                        "{\"repositories\":[\"skills\"],\"permissions\":{\"contents\":\"read\"}}",
                        "{\"repositories\":[\"other\"],\"permissions\":{\"contents\":\"read\"}}"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0058"})
    void a_token_that_expires_within_the_margin_is_never_reused() {
        lifetime.set(Duration.ofMinutes(5));
        GitHubAppTokens tokens = tokens("77");

        tokens.token(PREFIX + "skills.git", false);
        assertThat(tokens.token(PREFIX + "skills.git", false).cached()).isFalse();
        assertThat(minted).hasValue(2);
    }

    /** A clock a test moves by hand. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
