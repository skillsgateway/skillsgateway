package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The facade's second credential kind, attacked (GW_AUTH_0040, GW_AUTH_0041, GW_AUTH_0042).
 *
 * <p>Its own Spring context: whether the facade accepts identity-provider bearer tokens is a
 * deployment decision with no per-request form, so the only way to exercise both states is to run
 * two gateways. {@link IdpBearerDisabledTests} is the other one, and it runs in the shared default
 * context precisely because the default is "off".
 *
 * <p>Every refusal below is checked against the reference advertisement rather than a clone,
 * because that is the first request a git client makes: a credential refused there transfers
 * nothing, which is the property being claimed. The one positive case runs a real {@code git
 * clone}, because "the protocol works end to end with this header" is not something an HTTP
 * assertion can stand in for.
 */
@TestPropertySource(properties = "skills-gateway.facade.idp-bearer.enabled=true")
class IdpBearerFacadeTests extends AbstractGatewayTest {

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.idp.jwk-set-uri", IdpBearerFixture::jwkSetUri);
        registry.add("skills-gateway.oidc.issuer", () -> IdpBearerFixture.ISSUER);
    }

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    @SVCs({"SVC_GW_AUTH_0040"})
    void an_identity_provider_token_clones_and_every_foreign_or_malformed_one_is_refused() throws Exception {
        String name = uniqueName("sso");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        // The positive case, over the real protocol: a plain git clone whose only credential is an
        // SSO bearer token in a header. No PAT exists anywhere in this test.
        GitResult clone = git(
                null,
                "-c",
                "http.extraHeader=Authorization: Bearer " + IdpBearerFixture.validToken("sso-alice"),
                "clone",
                facadeUrl(name, null),
                newWorkDir("sso-clone").toString());
        assertThat(clone.exitCode()).as(clone.output()).isZero();

        // The failure model, one token at a time. Each is well-formed and wrong in exactly one way,
        // so a pass means that one check is doing the work rather than the request failing early.
        assertThat(advertisementWith(
                        name,
                        IdpBearerFixture.token("sso-alice", claims -> claims.issuer(IdpBearerFixture.FOREIGN_ISSUER))))
                .as("a token from another tenant of the same endpoint")
                .isEqualTo(401);
        assertThat(advertisementWith(
                        name, IdpBearerFixture.token("sso-alice", claims -> claims.audience("some-other-app"))))
                .as("a token minted for another application")
                .isEqualTo(401);
        assertThat(advertisementWith(name, IdpBearerFixture.token("sso-alice", expired())))
                .as("an expired token")
                .isEqualTo(401);
        assertThat(advertisementWith(name, IdpBearerFixture.token("sso-alice", notYetValid())))
                .as("a token whose not-before has not arrived")
                .isEqualTo(401);
        assertThat(advertisementWith(name, IdpBearerFixture.tokenSignedByAnUnknownKey("sso-alice")))
                .as("a token signed by a key the gateway's key set does not publish")
                .isEqualTo(401);
        assertThat(advertisementWith(
                        name, IdpBearerFixture.token("sso-alice", claims -> claims.audience((List<String>) null))))
                .as("a token carrying no audience at all")
                .isEqualTo(401);
        assertThat(advertisementWith(name, newPat()))
                .as("a perfectly valid personal access token, in the wrong header")
                .isEqualTo(401);
        assertThat(advertisementWith(name, "not-a-token-at-all"))
                .as("a value that is not a token in any scheme")
                .isEqualTo(401);

        // And the credential that works still works, on the same endpoint, in the same run.
        assertThat(advertisementWith(name, IdpBearerFixture.validToken("sso-alice")))
                .isEqualTo(200);
    }

    /**
     * The challenge is what makes every git client in existence work, and adding an authentication
     * mechanism to a Spring Security chain is exactly the change that quietly rewrites it. An
     * anonymous request must still be told to use Basic, or the credential helper never offers the
     * PAT that is still the documented default.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0040"})
    void the_basic_challenge_is_unchanged_by_the_second_credential_kind() throws Exception {
        String name = uniqueName("challenge");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        HttpResponse<String> anonymous = HTTP.send(
                HttpRequest.newBuilder(URI.create(advertisementUrl(name))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Basic");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0041"})
    void the_ledger_says_which_kind_of_credential_fetched() throws Exception {
        String name = uniqueName("kinds");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        assertThat(advertisementWith(name, IdpBearerFixture.validToken("sso-bob")))
                .isEqualTo(200);
        String pat = newPat();
        assertThat(advertisementWithAuthorization(name, "Basic " + basic(pat))).isEqualTo(200);

        List<Map<String, Object>> entries = fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")))
                .filter(row -> "info-refs".equals(row.get("event")))
                .toList();

        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.get("principal")).isEqualTo("sso-bob");
            assertThat(entry.get("credential_kind")).isEqualTo("idp");
            // No gateway token row exists for this fetch, and the ledger says so honestly rather
            // than leaving the kind to be guessed from the null.
            assertThat(entry.get("token_id")).isNull();
        });
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.get("principal")).isEqualTo("alice");
            assertThat(entry.get("credential_kind")).isEqualTo("pat");
            assertThat(entry.get("token_id")).isNotNull();
        });
    }

    /**
     * The facade has never consulted roles, for any credential kind — its authorization is token
     * scopes, and an identity-provider token carries none, which makes it exactly an unscoped PAT
     * (design.md, D6). Pinned here so that giving the facade a role check later is a deliberate act
     * with a failing test attached, rather than a silent divergence in which an SSO token is more
     * restricted than the PAT the portal mints for the same person.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0040"})
    void a_principal_holding_no_role_still_fetches_exactly_as_an_unscoped_token_does() throws Exception {
        String name = uniqueName("norole");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        assertThat(advertisementWith(name, IdpBearerFixture.validToken("nobody-with-no-mapped-role")))
                .isEqualTo(200);
    }

    /**
     * The two boundaries stay separate (GW_AUTH_0042). The machine API chain matches {@code /api/**}
     * plus the presence of a bearer header, so this token reaches it — and must be refused there,
     * because the credential that clones is not the credential that administers.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0042"})
    void a_token_the_facade_accepts_reaches_no_administrative_endpoint() throws Exception {
        String token = IdpBearerFixture.validToken("sso-alice");

        mockMvc.perform(get("/api/marketplaces").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private static Consumer<JWTClaimsSet.Builder> expired() {
        return claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(7200)));
    }

    private static Consumer<JWTClaimsSet.Builder> notYetValid() {
        return claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().plusSeconds(7200)));
    }

    private int advertisementWith(String marketplace, String bearer) throws IOException, InterruptedException {
        return advertisementWithAuthorization(marketplace, "Bearer " + bearer);
    }

    private int advertisementWithAuthorization(String marketplace, String authorization)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(advertisementUrl(marketplace)))
                .header("Authorization", authorization)
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String advertisementUrl(String marketplace) {
        return facadeUrl(marketplace, null) + "/info/refs?service=git-upload-pack";
    }

    private static String basic(String pat) {
        return java.util.Base64.getEncoder()
                .encodeToString(("token:" + pat).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
