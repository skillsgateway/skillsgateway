package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.ingestion.UpstreamFailure;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.sync.SyncService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;

/**
 * A failed ingest says why (GW_INGEST_0038), is recorded on the marketplace (GW_INGEST_0039) and in the
 * ledger (GW_AUDIT_0010), whatever triggered it. Upstreams are in-process or on disk.
 */
@ExtendWith(OutputCaptureExtension.class)
class IngestFailureTests extends AbstractGatewayTest {

    private static GitHttpFixture forge;

    @Autowired
    private SyncService syncService;

    @BeforeAll
    static void startForge() {
        try {
            forge = new GitHttpFixture();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
    @SVCs({"SVC_GW_INGEST_0038", "SVC_GW_INGEST_0039", "SVC_GW_AUDIT_0010"})
    void a_failed_ingest_says_why_and_is_recorded_and_a_later_success_clears_it(CapturedOutput output)
            throws Exception {
        String repo = forge.publish("acme/" + uniqueName("flaky"), Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String name = uniqueName("flaky");
        register(name, forge.baseUrl() + "/" + repo + ".git");
        String me = JsonPath.read(
                mockMvc.perform(get("/api/v1/me").with(oidcLogin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.username");

        forge.unauthorized("/" + repo);
        mockMvc.perform(post("/api/v1/marketplaces/%s/ingest".formatted(name)).with(oidcLogin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", Matchers.containsString(UpstreamFailure.NOT_FOUND_OR_AUTH)))
                .andExpect(jsonPath("$.reason").value(UpstreamFailure.NOT_FOUND_OR_AUTH))
                .andExpect(jsonPath("$.rootCause", Matchers.not(Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.nextStep", Matchers.not(Matchers.emptyOrNullString())));

        Map<String, Object> failed = listed(name);
        assertThat(failed.get("lastIngestOutcome")).isEqualTo("failed");
        assertThat((String) failed.get("lastIngestReason")).contains(UpstreamFailure.NOT_FOUND_OR_AUTH);
        assertThat(failed.get("lastIngestAt")).isNotNull();
        assertThat(fetchLogRepository.list()).anySatisfy(row -> {
            assertThat(row.get("marketplace")).isEqualTo(name);
            assertThat(row.get("event")).isEqualTo("ingest-failed");
            assertThat(row.get("principal")).isEqualTo(me);
            assertThat((String) row.get("detail")).contains(UpstreamFailure.NOT_FOUND_OR_AUTH);
        });
        assertThat(output.getOut() + output.getErr())
                .containsPattern("WARN.*" + name + ".*" + UpstreamFailure.NOT_FOUND_OR_AUTH);

        forge.reset();
        mockMvc.perform(post("/api/v1/marketplaces/%s/ingest".formatted(name)).with(oidcLogin()))
                .andExpect(status().isCreated());

        Map<String, Object> recovered = listed(name);
        assertThat(recovered.get("lastIngestOutcome")).isEqualTo("succeeded");
        assertThat(recovered.get("lastIngestReason")).isNull();
        assertThat(Instant.parse((String) recovered.get("lastIngestAt")))
                .isAfterOrEqualTo(Instant.parse((String) failed.get("lastIngestAt")));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0038"})
    void the_head_fetch_error_stays_attached_to_the_fallbacks() throws Exception {
        String repo = forge.publish("acme/" + uniqueName("fallback"), Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String name = uniqueName("fallback");
        register(name, forge.baseUrl() + "/" + repo + ".git");
        Marketplace marketplace = marketplaceRepository.findByName(name).orElseThrow();
        forge.unauthorized("/" + repo);

        Throwable thrown = catchThrowable(() -> ingestionService.ingest(marketplace, "alice"));

        assertThat(thrown).isNotNull();
        assertThat(chainHasSuppressed(thrown))
                .as("the first (HEAD refspec) attempt's error is attached to the fallback's")
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0039", "SVC_GW_AUDIT_0010"})
    void an_automated_ingest_failure_is_recorded_the_same_way() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("gone");
        register(name, upstream.toUri().toString());
        syncService.changeMode(name, Marketplace.SYNC_SCHEDULED, "root");
        FileSystemUtils.deleteRecursively(upstream);

        syncService.sweep(Integer.MAX_VALUE);

        Map<String, Object> failed = listed(name);
        assertThat(failed.get("lastIngestOutcome")).isEqualTo("failed");
        assertThat(failed.get("lastIngestReason")).isNotNull();
        assertThat(fetchLogRepository.list()).anySatisfy(row -> {
            assertThat(row.get("marketplace")).isEqualTo(name);
            assertThat(row.get("event")).isEqualTo("ingest-failed");
            assertThat(row.get("principal")).isEqualTo(SyncService.SCHEDULER_ACTOR);
        });
    }

    private static boolean chainHasSuppressed(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current.getSuppressed().length > 0) {
                return true;
            }
        }
        return false;
    }

    private void register(String name, String url) throws Exception {
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> listed(String name) throws Exception {
        String body = mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> matches = JsonPath.read(body, "$[?(@.name == '%s')]".formatted(name));
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }
}
