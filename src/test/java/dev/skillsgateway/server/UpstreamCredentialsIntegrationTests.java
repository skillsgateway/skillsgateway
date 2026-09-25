package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.ingestion.UpstreamFailure;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
 * Private upstreams read with the credential configured for their URL prefix (GW_INGEST_0050), sent
 * nowhere else (GW_INGEST_0051), repeated nowhere (GW_INGEST_0052), and named in the registration
 * ledger entry (GW_INGEST_0055). The credentials are the ones {@link AbstractExternalSourceTest}
 * configures for its forge; a second forge on another port has none.
 */
@ExtendWith(OutputCaptureExtension.class)
class UpstreamCredentialsIntegrationTests extends AbstractExternalSourceTest {

    private static final List<String> TOKENS = List.of(HOST_TOKEN, PRIVATE_TOKEN, OTHER_TOKEN);

    /** On another port of the same host, so under none of the configured prefixes. */
    private static GitHttpFixture elsewhere;

    private final List<String> bodies = new ArrayList<>();

    @BeforeAll
    static void publish() throws Exception {
        FORGE.publish("private/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        FORGE.publish("public/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        FORGE.publish("other/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        FORGE.publish("locked/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        elsewhere = new GitHttpFixture();
        elsewhere.publish("acme/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
    }

    @AfterAll
    static void stopElsewhere() {
        elsewhere.close();
    }

    private static String basic(String token) {
        return "Basic " + Base64.getEncoder().encodeToString(("sgw:" + token).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0050"})
    void a_private_upstream_registers_and_ingests_with_the_longest_prefix_credential() throws Exception {
        FORGE.requireBasic("/private/", "sgw", PRIVATE_TOKEN);
        String name = uniqueName("private");

        register(name, FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());
        ingest(name)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sha").value(FORGE.headSha("private/skills")));

        List<String[]> seen = FORGE.authorizations();
        assertThat(seen)
                .filteredOn(request -> request[0].startsWith("/private/"))
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(request -> assertThat(request[1]).isEqualTo(basic(PRIVATE_TOKEN)));
        // The forge-metadata lookup at registration is not a git request; the credential is not for it.
        assertThat(seen)
                .filteredOn(request -> !request[0].startsWith("/private/"))
                .allSatisfy(request -> {
                    assertThat(request[0]).startsWith("/api/");
                    assertThat(request[1]).isEmpty();
                });
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0050"})
    void an_upstream_under_no_prefix_is_read_without_a_credential() throws Exception {
        elsewhere.reset();

        register(uniqueName("anon"), elsewhere.baseUrl() + "/acme/skills.git").andExpect(status().isCreated());

        assertThat(elsewhere.authorizations()).isNotEmpty().allSatisfy(request -> assertThat(request[1])
                .isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0052", "SVC_GW_INGEST_0050"})
    void a_refused_token_reads_as_not_found_or_authentication() throws Exception {
        FORGE.requireBasic("/locked/", "sgw", "tok-the-forge-wants-another");
        String name = uniqueName("locked");

        register(name, FORGE.baseUrl() + "/locked/skills.git")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH));

        assertThat(FORGE.authorizations())
                .anySatisfy(request -> assertThat(request[1]).isEqualTo(basic(HOST_TOKEN)));
        assertThat(marketplaceRepository.findByName(name)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void a_redirect_to_another_port_of_the_same_host_carries_no_credential() throws Exception {
        FORGE.requireBasic("/private/", "sgw", PRIVATE_TOKEN);
        elsewhere.reset();
        FORGE.redirectTo(elsewhere.baseUrl() + "/acme/skills.git/info/refs?service=git-upload-pack", 1);

        register(uniqueName("port"), FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());

        assertThat(elsewhere.authorizations()).isNotEmpty().allSatisfy(request -> assertThat(request[1])
                .isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void a_redirect_to_another_path_of_the_same_host_carries_no_credential() throws Exception {
        FORGE.requireBasic("/private/", "sgw", PRIVATE_TOKEN);
        FORGE.redirectTo(FORGE.baseUrl() + "/public/skills.git/info/refs?service=git-upload-pack", 1);

        register(uniqueName("path"), FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());

        assertThat(FORGE.authorizations())
                .anySatisfy(request -> assertThat(request[0]).startsWith("/public/"))
                .filteredOn(request -> !request[0].startsWith("/private/"))
                .allSatisfy(request -> assertThat(request[1]).isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0051"})
    void a_redirect_into_another_credentialed_prefix_does_not_switch_tokens() throws Exception {
        FORGE.requireBasic("/private/", "sgw", PRIVATE_TOKEN);
        FORGE.redirectTo(FORGE.baseUrl() + "/other/skills.git/info/refs?service=git-upload-pack", 1);

        register(uniqueName("switch"), FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());

        assertThat(FORGE.authorizations())
                .anySatisfy(request -> assertThat(request[0]).startsWith("/other/"))
                .filteredOn(request -> request[0].startsWith("/other/"))
                .allSatisfy(request -> assertThat(request[1]).isEmpty());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0052"})
    void the_token_is_never_repeated(CapturedOutput output) throws Exception {
        String refused = uniqueName("refused");
        FORGE.requireBasic("/locked/", "sgw", "tok-the-forge-wants-another");
        register(refused, FORGE.baseUrl() + "/locked/skills.git").andExpect(status().isBadGateway());

        String revoked = uniqueName("revoked");
        FORGE.requireBasic("/private/", "sgw", PRIVATE_TOKEN);
        register(revoked, FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());
        FORGE.requireBasic("/private/", "sgw", "tok-rotated-at-the-forge");
        ingest(revoked)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH));
        remember(mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn());
        remember(mockMvc.perform(get("/api/v1/audit").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn());

        for (String token : TOKENS) {
            assertThat(bodies).noneMatch(body -> body.contains(token));
            assertThat(output.getAll()).doesNotContain(token);
            assertThat(rowsContaining("fetch_log", token)).isZero();
            assertThat(rowsContaining("marketplaces", token)).isZero();
        }
        assertThat(marketplaceRepository.findByName(revoked).orElseThrow().lastIngestReason())
                .contains(UpstreamFailure.NOT_FOUND_OR_AUTH);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0054"})
    void a_clone_url_with_userinfo_is_refused_before_the_upstream_is_contacted() throws Exception {
        for (String userinfo : List.of("sgw:" + PRIVATE_TOKEN + "@", "sgw@")) {
            FORGE.reset();
            String name = uniqueName("userinfo");
            String url = FORGE.baseUrl().replace("http://", "http://" + userinfo) + "/public/skills.git";

            register(name, url).andExpect(status().isBadRequest());

            assertThat(FORGE.requestedPaths()).isEmpty();
            assertThat(marketplaceRepository.findByName(name)).isEmpty();
            assertThat(fetchLogRepository.list()).noneMatch(row -> name.equals(row.get("marketplace")));
        }
        assertThat(bodies).noneMatch(body -> body.contains(PRIVATE_TOKEN));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0055"})
    void the_registration_ledger_entry_names_the_prefix_and_never_the_token() throws Exception {
        String credentialed = uniqueName("named");
        String anonymous = uniqueName("unnamed");
        elsewhere.reset();

        register(credentialed, FORGE.baseUrl() + "/private/skills.git").andExpect(status().isCreated());
        register(anonymous, elsewhere.baseUrl() + "/acme/skills.git").andExpect(status().isCreated());

        assertThat(registrationDetail(credentialed))
                .contains("credential=" + FORGE.baseUrl() + "/private/")
                .doesNotContain(PRIVATE_TOKEN);
        assertThat(registrationDetail(anonymous)).doesNotContain("credential=");
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
