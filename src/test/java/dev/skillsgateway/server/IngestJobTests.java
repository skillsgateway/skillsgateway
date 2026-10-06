package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.ingestion.UpstreamFailure;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.sync.SyncService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An on-demand ingest answers before it fetches and is followed through its status (GW_INGEST_0068);
 * an ingest whose replica stopped reads as interrupted and does not block the next (GW_INGEST_0069).
 */
class IngestJobTests extends AbstractGatewayTest {

    private static GitHttpFixture forge;

    @Autowired
    private SyncService syncService;

    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void startForge() throws Exception {
        forge = new GitHttpFixture();
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
    @SVCs({"SVC_GW_INGEST_0068"})
    void an_ingest_answers_before_the_upstream_does_and_is_followed_to_its_snapshot() throws Exception {
        String repo = forge.publish("acme/" + uniqueName("slow"), Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String name = uniqueName("slow");
        Marketplace marketplace = marketplaceRepository.register(name, forge.baseUrl() + "/" + repo + ".git");
        CountDownLatch release = new CountDownLatch(1);
        forge.hold("/" + repo, release);

        mockMvc.perform(post("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/marketplaces/" + name + "/ingest"))
                .andExpect(jsonPath("$.marketplace").value(name))
                .andExpect(jsonPath("$.running.stage", Matchers.oneOf("queued", "fetching")))
                .andExpect(jsonPath("$.running.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.running.interrupted").value(false))
                .andExpect(jsonPath("$.last").doesNotExist());

        String fetching = awaitStage(name, IngestionService.STAGE_FETCHING);
        String startedAt = JsonPath.read(fetching, "$.running.startedAt");

        // A second request follows the ingest in progress rather than starting another.
        mockMvc.perform(post("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.running.stage").value(IngestionService.STAGE_FETCHING))
                .andExpect(jsonPath("$.running.startedAt").value(startedAt));

        release.countDown();
        String done = awaitIngest(name, oidcLogin());

        assertThat((String) JsonPath.read(done, "$.last.outcome")).isEqualTo(Marketplace.INGEST_SUCCEEDED);
        assertThat((String) JsonPath.read(done, "$.last.snapshotState")).isEqualTo(Snapshot.HELD);
        assertThat((Object) JsonPath.read(done, "$.last.reason")).isNull();
        long snapshotId = ((Number) JsonPath.read(done, "$.last.snapshotId")).longValue();
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().marketplaceId())
                .isEqualTo(marketplace.id());
        assertThat(snapshotRepository
                        .listByMarketplaces(java.util.List.of(marketplace.id()))
                        .get(marketplace.id()))
                .as("the second request started nothing")
                .hasSize(1);
        mockMvc.perform(get("/api/v1/marketplaces").with(oidcLogin()))
                .andExpect(jsonPath("$[?(@.name == '%s')].lastIngestSnapshotId".formatted(name))
                        .value(Matchers.contains((int) snapshotId)));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0068"})
    void a_rejected_manifest_ends_with_its_snapshot_rejected() throws Exception {
        Path upstream = createUpstream("{ not a manifest");
        String name = uniqueName("rejected");
        marketplaceRepository.register(name, upstream.toAbsolutePath().toString());

        String done = ingestViaApi(name, oidcLogin());

        assertThat((String) JsonPath.read(done, "$.last.outcome")).isEqualTo(Marketplace.INGEST_SUCCEEDED);
        assertThat((String) JsonPath.read(done, "$.last.snapshotState")).isEqualTo(Snapshot.REJECTED);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0068"})
    void an_unreadable_upstream_ends_failed_with_reason_root_cause_and_next_step() throws Exception {
        String repo = forge.publish("acme/" + uniqueName("private"), Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String name = uniqueName("private");
        marketplaceRepository.register(name, forge.baseUrl() + "/" + repo + ".git");
        forge.unauthorized("/" + repo);

        String done = ingestViaApi(name, oidcLogin());

        assertThat((String) JsonPath.read(done, "$.last.outcome")).isEqualTo(Marketplace.INGEST_FAILED);
        assertThat((Object) JsonPath.read(done, "$.last.snapshotId")).isNull();
        assertThat((String) JsonPath.read(done, "$.last.reason")).contains(UpstreamFailure.NOT_FOUND_OR_AUTH);
        assertThat((String) JsonPath.read(done, "$.last.failure.reason")).isEqualTo(UpstreamFailure.NOT_FOUND_OR_AUTH);
        assertThat((String) JsonPath.read(done, "$.last.failure.rootCause")).isNotBlank();
        assertThat((String) JsonPath.read(done, "$.last.failure.nextStep")).isNotBlank();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0068"})
    void an_automated_ingest_is_reported_the_same_way() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("swept");
        marketplaceRepository.register(name, upstream.toAbsolutePath().toString());
        syncService.changeMode(name, Marketplace.SYNC_SCHEDULED, "root");

        syncService.sweep(Integer.MAX_VALUE);

        String status = awaitIngest(name, oidcLogin());
        assertThat((String) JsonPath.read(status, "$.last.outcome")).isEqualTo(Marketplace.INGEST_SUCCEEDED);
        assertThat((String) JsonPath.read(status, "$.last.snapshotState")).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0068"})
    void an_unknown_marketplace_has_no_ingest_status() throws Exception {
        mockMvc.perform(get("/api/v1/marketplaces/{name}/ingest", uniqueName("nobody"))
                        .with(oidcLogin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0069"})
    void an_ingest_whose_heartbeat_lapsed_reads_interrupted_and_is_claimed_over() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("orphan");
        Marketplace marketplace =
                marketplaceRepository.register(name, upstream.toAbsolutePath().toString());
        jdbc.sql("UPDATE marketplaces SET ingest_attempt = :attempt, ingest_stage = 'vetting',"
                        + " ingest_started_at = now() - interval '5 minutes',"
                        + " ingest_heartbeat_at = now() - interval '2 minutes' WHERE id = :id")
                .param("attempt", UUID.randomUUID())
                .param("id", marketplace.id())
                .update();

        mockMvc.perform(get("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(jsonPath("$.running.stage").value(IngestionService.STAGE_VETTING))
                .andExpect(jsonPath("$.running.interrupted").value(true));

        String done = ingestViaApi(name, oidcLogin());

        assertThat((String) JsonPath.read(done, "$.last.outcome")).isEqualTo(Marketplace.INGEST_SUCCEEDED);
        assertThat((String) JsonPath.read(done, "$.last.snapshotState")).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0069"})
    void a_running_ingest_renews_its_heartbeat() throws Exception {
        String repo = forge.publish("acme/" + uniqueName("beat"), Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        String name = uniqueName("beat");
        Marketplace marketplace = marketplaceRepository.register(name, forge.baseUrl() + "/" + repo + ".git");
        CountDownLatch release = new CountDownLatch(1);
        forge.hold("/" + repo, release);
        mockMvc.perform(post("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(status().isAccepted());
        awaitStage(name, IngestionService.STAGE_FETCHING);
        jdbc.sql("UPDATE marketplaces SET ingest_heartbeat_at = now() - interval '2 minutes' WHERE id = :id")
                .param("id", marketplace.id())
                .update();
        mockMvc.perform(get("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(jsonPath("$.running.interrupted").value(true));

        ingestionService.heartbeat();

        mockMvc.perform(get("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                .andExpect(jsonPath("$.running.interrupted").value(false));
        release.countDown();
        awaitIngest(name, oidcLogin());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0069"})
    void a_live_ingest_is_not_claimed_over() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("live"), upstream.toAbsolutePath().toString());
        UUID running = UUID.randomUUID();
        marketplaceRepository.startIngest(marketplace.id(), running);

        assertThat(marketplaceRepository.claimIngest(
                        marketplace.id(), UUID.randomUUID(), IngestionService.HEARTBEAT_STALE))
                .isFalse();
        assertThat(stage(marketplace.name())).isEqualTo(IngestionService.STAGE_FETCHING);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0069"})
    void an_earlier_attempt_neither_moves_nor_clears_a_later_ones_progress() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("overlap"), upstream.toAbsolutePath().toString());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        marketplaceRepository.startIngest(marketplace.id(), first);
        marketplaceRepository.startIngest(marketplace.id(), second);

        marketplaceRepository.advanceIngest(marketplace.id(), first, IngestionService.STAGE_VETTING);
        assertThat(stage(marketplace.name())).isEqualTo(IngestionService.STAGE_FETCHING);

        marketplaceRepository.recordIngest(marketplace.id(), first, Marketplace.INGEST_SUCCEEDED, null, null, null);
        assertThat(stage(marketplace.name())).isEqualTo(IngestionService.STAGE_FETCHING);

        marketplaceRepository.recordIngest(marketplace.id(), second, Marketplace.INGEST_SUCCEEDED, null, null, null);
        assertThat(stage(marketplace.name())).isNull();
    }

    private String stage(String name) {
        return marketplaceRepository
                .ingestRecord(name, IngestionService.HEARTBEAT_STALE)
                .orElseThrow()
                .ingestStage();
    }

    private String awaitStage(String name, String stage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            String status = mockMvc.perform(
                            get("/api/v1/marketplaces/{name}/ingest", name).with(oidcLogin()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (stage.equals(JsonPath.read(status, "$.running.stage"))) {
                return status;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never reached '%s': %s".formatted(stage, status));
            }
            Thread.sleep(25);
        }
    }
}
