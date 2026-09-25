package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.ingestion.UpstreamFailure;
import io.github.reqstool.annotations.SVCs;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Registration reads the upstream before it creates anything (GW_INGEST_0040), and says why when it
 * cannot (GW_INGEST_0038). Every upstream here is in-process or on disk: nothing leaves the machine.
 */
class UpstreamReachabilityTests extends AbstractGatewayTest {

    private static GitHttpFixture forge;

    @BeforeAll
    static void startForge() throws Exception {
        forge = new GitHttpFixture();
        forge.publish("acme/skills", Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
    }

    @AfterAll
    static void stopForge() {
        forge.close();
    }

    @BeforeEach
    void resetForge() {
        forge.reset();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040", "SVC_GW_INGEST_0038"})
    void a_repository_that_does_not_exist_is_refused_and_nothing_is_created() throws Exception {
        String name = uniqueName("typo");

        register(name, forge.baseUrl() + "/acme/missing.git")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", Matchers.containsString(UpstreamFailure.NOT_FOUND_OR_AUTH)))
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH))
                .andExpect(jsonPath("$.rootCause", Matchers.not(Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.nextStep", Matchers.not(Matchers.emptyOrNullString())));

        assertNothingCreated(name);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040", "SVC_GW_INGEST_0038"})
    void a_repository_that_answers_401_is_refused_the_same_way() throws Exception {
        forge.unauthorized("/private/");
        String name = uniqueName("private");

        register(name, forge.baseUrl() + "/private/skills.git")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH));

        assertThat(forge.requestedPaths()).anyMatch(path -> path.startsWith("/private/"));
        assertNothingCreated(name);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040", "SVC_GW_INGEST_0038"})
    void an_upstream_that_cannot_be_reached_is_refused() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        String name = uniqueName("down");

        register(name, "http://127.0.0.1:%d/acme/skills.git".formatted(closed))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.UNREACHABLE));

        assertNothingCreated(name);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040"})
    void a_repository_with_no_default_branch_is_refused() throws Exception {
        Path empty = newWorkDir("empty");
        try (Git ignored =
                Git.init().setDirectory(empty.toFile()).setInitialBranch("main").call()) {
            // No commit: nothing for the gateway to pin.
        }
        String name = uniqueName("empty");

        register(name, empty.toUri().toString())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NO_DEFAULT_BRANCH));

        assertNothingCreated(name);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040"})
    void a_readable_upstream_is_registered_over_http_and_file() throws Exception {
        String overHttp = uniqueName("http");
        String overFile = uniqueName("file");

        register(overHttp, forge.baseUrl() + "/acme/skills.git").andExpect(status().isCreated());
        register(overFile, createUpstream(DEFAULT_MANIFEST).toUri().toString()).andExpect(status().isCreated());

        assertThat(listedNames()).contains(overHttp, overFile);
        assertThat(forge.wasRequested("/acme/skills.git/info/refs")).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0040"})
    void a_request_refused_without_the_network_never_contacts_the_upstream() throws Exception {
        String name = uniqueName("taken");
        register(name, createUpstream(DEFAULT_MANIFEST).toUri().toString()).andExpect(status().isCreated());

        register(name, forge.baseUrl() + "/acme/conflict-probe.git").andExpect(status().isConflict());

        assertThat(forge.requestedPaths()).noneMatch(path -> path.contains("conflict-probe"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0038", "SVC_GW_INGEST_0040", "SVC_GW_INGEST_0054"})
    void a_credential_in_the_url_is_never_repeated() throws Exception {
        String name = uniqueName("secret");
        String url = forge.baseUrl().replace("http://", "http://user:s3cret@") + "/acme/missing.git";

        // Refused before the upstream is read since GW_INGEST_0054; the redaction on the 502 path is
        // UpstreamFailureTests' to pin.
        String body = register(name, url)
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("s3cret");
        assertThat(fetchLogRepository.list())
                .noneMatch(row -> String.valueOf(row.get("detail")).contains("s3cret"));
        assertNothingCreated(name);
    }

    private ResultActions register(String name, String url) throws Exception {
        return mockMvc.perform(post("/api/v1/marketplaces")
                .with(oidcLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)));
    }

    private List<String> listedNames() throws Exception {
        String body = mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].name");
    }

    private void assertNothingCreated(String name) throws Exception {
        assertThat(listedNames()).doesNotContain(name);
        assertThat(marketplaceRepository.findByName(name)).isEmpty();
        assertThat(fetchLogRepository.list())
                .noneMatch(row ->
                        name.equals(row.get("marketplace")) && "marketplace-registered".equals(row.get("event")));
    }
}
