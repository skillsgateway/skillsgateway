package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.skillsgateway.server.GitHttpFixture.ApiRequest;
import dev.skillsgateway.server.ingestion.UpstreamFailure;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Private upstreams read with a GitHub App installation token (GW_INGEST_0056 to GW_INGEST_0062,
 * GW_AUTH_0049, GW_AUTH_0051). {@link AbstractExternalSourceTest} configures three App entries on
 * its forge and a second fixture, {@code GITHUB_API}, that plays GitHub's REST API. The token cache
 * lives in the shared context, so every test reads its own repository.
 */
@ExtendWith(OutputCaptureExtension.class)
class UpstreamGitHubAppIntegrationTests extends AbstractExternalSourceTest {

    private static final String LOOKED_UP = "55";

    /** On another port of the same host, so under none of the configured prefixes. */
    private static GitHttpFixture elsewhere;

    private final List<String> bodies = new ArrayList<>();

    @BeforeAll
    static void publish() throws Exception {
        FORGE.publish("public/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        FORGE.publish("private/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        elsewhere = new GitHttpFixture();
        elsewhere.publish("acme/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
    }

    @AfterAll
    static void stopElsewhere() {
        elsewhere.close();
    }

    /** A fresh repository under {@code owner}, so no other test's cached token applies to it. */
    private static String repository(String owner) throws Exception {
        String name = uniqueName("r");
        FORGE.publish(owner + "/" + name, Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        return name;
    }

    private static String basic(String token) {
        return "Basic "
                + Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
    }

    /** The API answers the installation lookup for {@code appco/repo} with installation 55. */
    private static void installed(String repo) {
        GITHUB_API.respond("GET", "/repos/appco/" + repo + "/installation", 200, "{\"id\":" + LOOKED_UP + "}");
    }

    /** The API issues {@code token} from {@code installation}, expiring after {@code lifetime}. */
    private static void issues(String installation, String token, Duration lifetime) {
        GITHUB_API.respond(
                "POST",
                "/app/installations/" + installation + "/access_tokens",
                201,
                "{\"token\":\"%s\",\"expires_at\":\"%s\",\"permissions\":{\"contents\":\"read\"}}"
                        .formatted(token, Instant.now().plus(lifetime)));
    }

    private static long tokenRequests() {
        return GITHUB_API.apiRequests().stream()
                .filter(request -> request.method().equals("POST"))
                .count();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0056", "SVC_GW_INGEST_0057", "SVC_GW_INGEST_0058", "SVC_GW_AUTH_0051"})
    void a_private_upstream_registers_and_ingests_with_a_minted_installation_token() throws Exception {
        String repo = repository("appco");
        String token = "ghs_minted_" + repo;
        installed(repo);
        issues(LOOKED_UP, token, Duration.ofHours(1));
        FORGE.requireBasic("/appco/", "x-access-token", token);
        String name = uniqueName("app");

        register(name, FORGE.baseUrl() + "/appco/" + repo + ".git").andExpect(status().isCreated());
        ingest(name).andExpect(status().isCreated());
        ingest(name).andExpect(status().isCreated());

        assertThat(FORGE.authorizations())
                .filteredOn(request -> request[0].startsWith("/appco/"))
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(request -> assertThat(request[1]).isEqualTo(basic(token)));
        List<ApiRequest> api = GITHUB_API.apiRequests();
        assertThat(api)
                .extracting(ApiRequest::method, ApiRequest::path)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GET", "/repos/appco/" + repo + "/installation"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/app/installations/55/access_tokens"));
        // One repository, contents read, nothing else (GW_INGEST_0057).
        assertThat(api.get(1).body())
                .isEqualTo("{\"repositories\":[\"" + repo + "\"],\"permissions\":{\"contents\":\"read\"}}");
        for (ApiRequest request : api) {
            assertThat(request.authorization()).startsWith("Bearer ");
            SignedJWT jwt = SignedJWT.parse(request.authorization().substring("Bearer ".length()));
            assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) APP_KEY.getPublic())))
                    .isTrue();
            assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(APP_ID);
        }
        // The assertion never rides on a git request (GW_AUTH_0051).
        assertThat(FORGE.authorizations())
                .noneSatisfy(request -> assertThat(request[1]).startsWith("Bearer"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0056"})
    void a_configured_installation_is_minted_from_without_a_lookup() throws Exception {
        String repo = repository("pinned");
        issues("77", "ghs_pinned_" + repo, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_pinned_" + repo);

        register(uniqueName("pinned"), FORGE.baseUrl() + "/pinned/" + repo + ".git")
                .andExpect(status().isCreated());

        assertThat(GITHUB_API.apiRequests())
                .extracting(ApiRequest::path)
                .containsExactly("/app/installations/77/access_tokens");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0058"})
    void a_token_close_to_its_expiry_is_renewed_for_every_read() throws Exception {
        String repo = repository("pinned");
        issues("77", "ghs_short_" + repo, Duration.ofMinutes(2));
        String name = uniqueName("short");

        register(name, FORGE.baseUrl() + "/pinned/" + repo + ".git").andExpect(status().isCreated());
        ingest(name).andExpect(status().isCreated());
        ingest(name).andExpect(status().isCreated());

        assertThat(tokenRequests()).isEqualTo(3);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0058"})
    void a_refused_cached_token_is_renewed_once_and_only_once() throws Exception {
        String repo = repository("pinned");
        String name = uniqueName("renew");
        issues("77", "ghs_first_" + repo, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_first_" + repo);
        register(name, FORGE.baseUrl() + "/pinned/" + repo + ".git").andExpect(status().isCreated());
        assertThat(tokenRequests()).isEqualTo(1);

        // Revoked early: the upstream now wants the token the API now issues.
        issues("77", "ghs_second_" + repo, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_second_" + repo);
        ingest(name).andExpect(status().isCreated());
        assertThat(tokenRequests()).isEqualTo(2);

        // A lasting refusal: the renewed token is refused too, and nothing mints a third time.
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_nobody_has_this");
        ingest(name)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH));
        assertThat(tokenRequests()).isEqualTo(3);

        // The refused token was dropped: the next read mints, is refused, and is not retried.
        ingest(name).andExpect(status().isBadGateway());
        assertThat(tokenRequests()).isEqualTo(4);
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0051"})
    void a_redirect_to_another_port_carries_no_installation_token() throws Exception {
        String repo = repository("pinned");
        issues("77", "ghs_port_" + repo, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_port_" + repo);
        elsewhere.reset();
        FORGE.redirectTo(elsewhere.baseUrl() + "/acme/skills.git/info/refs?service=git-upload-pack", 1);

        register(uniqueName("port"), FORGE.baseUrl() + "/pinned/" + repo + ".git")
                .andExpect(status().isCreated());

        assertThat(elsewhere.authorizations()).isNotEmpty().allSatisfy(request -> assertThat(request[1])
                .isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0051"})
    void a_redirect_to_another_path_carries_no_installation_token() throws Exception {
        String repo = repository("pinned");
        issues("77", "ghs_path_" + repo, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_path_" + repo);
        FORGE.redirectTo(FORGE.baseUrl() + "/public/skills.git/info/refs?service=git-upload-pack", 1);

        register(uniqueName("path"), FORGE.baseUrl() + "/pinned/" + repo + ".git")
                .andExpect(status().isCreated());

        assertThat(FORGE.authorizations())
                .anySatisfy(request -> assertThat(request[0]).startsWith("/public/"))
                .filteredOn(request -> !request[0].startsWith("/pinned/"))
                .allSatisfy(request -> assertThat(request[1]).isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0051"})
    void a_redirect_from_the_api_is_not_followed() throws Exception {
        String repo = repository("pinned");
        GITHUB_API.respond(
                "POST", "/app/installations/77/access_tokens", 302, "", "Location", FORGE.baseUrl() + "/stolen");
        String name = uniqueName("apiredirect");

        register(name, FORGE.baseUrl() + "/pinned/" + repo + ".git")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.APP_NO_TOKEN));

        assertThat(FORGE.wasRequested("/stolen")).isFalse();
        assertThat(marketplaceRepository.findByName(name)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0061"})
    void the_api_is_only_ever_the_configured_one() throws Exception {
        String repo = repository("appco");
        installed(repo);
        issues(LOOKED_UP, "ghs_only_" + repo, Duration.ofHours(1));

        register(uniqueName("only"), FORGE.baseUrl() + "/appco/" + repo + ".git")
                .andExpect(status().isCreated());
        assertThat(GITHUB_API.apiRequests()).hasSize(2);
        assertThat(FORGE.requestedPaths()).noneMatch(path -> path.startsWith("/repos/") || path.startsWith("/app/"));

        GITHUB_API.reset();
        for (String hostile : List.of("/appco/sk%3Fills.git", "/appco/a/b.git", "/appco/..git")) {
            String name = uniqueName("hostile");
            register(name, FORGE.baseUrl() + hostile)
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_A_GITHUB_REPOSITORY));
            assertThat(marketplaceRepository.findByName(name)).isEmpty();
        }
        assertThat(GITHUB_API.requestedPaths()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0059"})
    void each_api_refusal_is_reported_with_its_own_reason() throws Exception {
        String unlisted = repository("appco");
        GITHUB_API.respond("GET", "/repos/appco/" + unlisted + "/installation", 404, "{\"message\":\"Not Found\"}");
        expectRefusal("/appco/" + unlisted + ".git", UpstreamFailure.APP_NOT_INSTALLED);

        String[][] cases = {
            {
                "422",
                "There is at least one repository that does not exist or is not accessible",
                UpstreamFailure.APP_NOT_INSTALLED
            },
            {"404", "Not Found", UpstreamFailure.APP_INSTALLATION_NOT_FOUND},
            {"403", "This installation has been suspended", UpstreamFailure.APP_SUSPENDED},
            {"401", "A JSON web token could not be decoded", UpstreamFailure.APP_KEY_REFUSED},
            {
                "401",
                "'Issued at' claim ('iat') must be an Integer representing the time that the assertion was issued",
                UpstreamFailure.APP_CLOCK_SKEW
            },
            {"500", "Server Error", UpstreamFailure.APP_NO_TOKEN}
        };
        for (String[] refusal : cases) {
            String repo = repository("pinned");
            GITHUB_API.respond(
                    "POST",
                    "/app/installations/77/access_tokens",
                    Integer.parseInt(refusal[0]),
                    "{\"message\":\"%s\"}".formatted(refusal[1].replace("\"", "\\\"")));
            expectRefusal("/pinned/" + repo + ".git", refusal[2]);
        }

        expectRefusal("/deadapi/" + repository("deadapi") + ".git", UpstreamFailure.APP_API_UNREACHABLE);
    }

    private void expectRefusal(String path, String reason) throws Exception {
        String name = uniqueName("refused");
        register(name, FORGE.baseUrl() + path)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(reason))
                .andExpect(jsonPath("$.nextStep").isNotEmpty());
        assertThat(marketplaceRepository.findByName(name)).as(path).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0049"})
    void the_key_the_assertion_and_the_token_are_never_repeated(CapturedOutput output) throws Exception {
        String repo = repository("pinned");
        String token = "ghs_secret_" + repo;
        String name = uniqueName("leak");
        issues("77", token, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", token);
        register(name, FORGE.baseUrl() + "/pinned/" + repo + ".git").andExpect(status().isCreated());

        // Revoked, and the API's refusal to renew quotes the token it had issued.
        FORGE.requireBasic("/pinned/", "x-access-token", "ghs_someone_else");
        GITHUB_API.respond(
                "POST", "/app/installations/77/access_tokens", 500, "{\"message\":\"token " + token + " revoked\"}");
        ingest(name)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.APP_NO_TOKEN))
                .andExpect(jsonPath("$.rootCause", Matchers.containsString("token *** revoked")));

        // And a refused token on a fresh registration.
        String refusedRepo = repository("pinned");
        issues("77", "ghs_refused_" + refusedRepo, Duration.ofHours(1));
        register(uniqueName("leak"), FORGE.baseUrl() + "/pinned/" + refusedRepo + ".git")
                .andExpect(status().isBadGateway());

        // An upstream that quotes the token back: JGit repeats a refused redirect's Location in its error.
        String echoRepo = repository("pinned");
        String echoed = "ghs_echoed_" + echoRepo;
        issues("77", echoed, Duration.ofHours(1));
        FORGE.requireBasic("/pinned/", "x-access-token", echoed);
        FORGE.redirectTo(FORGE.baseUrl() + "/pinned/echo-" + echoed, Integer.MAX_VALUE);
        register(uniqueName("leak"), FORGE.baseUrl() + "/pinned/" + echoRepo + ".git")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.rootCause", Matchers.containsString("echo-***")));
        FORGE.redirectTo(null, 0);

        remember(mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn());
        remember(mockMvc.perform(get("/api/v1/audit").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn());

        List<String> secrets = new ArrayList<>(List.of(token, "ghs_refused_" + refusedRepo, echoed));
        GITHUB_API
                .apiRequests()
                .forEach(request -> secrets.add(request.authorization().substring("Bearer ".length())));
        APP_PEM.lines().filter(line -> !line.startsWith("-----")).forEach(secrets::add);
        assertThat(secrets).hasSizeGreaterThan(20);
        for (String secret : secrets) {
            assertThat(bodies).noneMatch(body -> body.contains(secret));
            assertThat(output.getAll()).doesNotContain(secret);
            assertThat(rowsContaining("fetch_log", secret)).isZero();
            assertThat(rowsContaining("marketplaces", secret)).isZero();
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0062"})
    void the_registration_ledger_entry_names_the_github_app_kind() throws Exception {
        String repo = repository("pinned");
        issues("77", "ghs_ledger_" + repo, Duration.ofHours(1));
        String app = uniqueName("ledgerapp");
        String stat = uniqueName("ledgerstatic");

        register(app, FORGE.baseUrl() + "/pinned/" + repo + ".git").andExpect(status().isCreated());
        register(stat, FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());

        assertThat(registrationDetail(app))
                .contains("credential=" + FORGE.baseUrl() + "/pinned/ (github-app)")
                .doesNotContain("ghs_ledger_");
        assertThat(registrationDetail(stat))
                .contains("credential=" + FORGE.baseUrl() + "/private/")
                .doesNotContain("github-app");
    }

    private String registrationDetail(String name) {
        return fetchLogRepository.list().stream()
                .filter(row -> name.equals(row.get("marketplace")) && "marketplace-registered".equals(row.get("event")))
                .map(row -> String.valueOf(row.get("detail")))
                .findFirst()
                .orElseThrow();
    }

    private long rowsContaining(String table, String text) {
        return jdbc.sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE :text")
                .param("text", "%" + text + "%")
                .query(Long.class)
                .single();
    }

    private ResultActions register(String name, String url) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/v1/marketplaces")
                .with(oidcLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)));
        remember(result.andReturn());
        return result;
    }

    private ResultActions ingest(String name) throws Exception {
        ResultActions result = mockMvc.perform(
                post("/api/v1/marketplaces/%s/ingest".formatted(name)).with(oidcLogin()));
        remember(result.andReturn());
        return result;
    }

    private void remember(MvcResult result) throws Exception {
        bodies.add(result.getResponse().getContentAsString());
        if (result.getResolvedException() != null) {
            bodies.add(String.valueOf(result.getResolvedException().getMessage()));
        }
    }
}
