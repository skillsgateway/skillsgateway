package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.sync.InboundWebhookController;
import dev.skillsgateway.server.sync.SyncService;
import dev.skillsgateway.server.webhook.WebhookSigner;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;

/**
 * Upstream sync modes (GW_0056–GW_0060): the mode policy and its show-once secret, the bounded
 * oldest-first polling sweep, the HMAC-gated inbound webhook including its adversarial cases, the
 * upstream-outage resilience guarantee, and the ledger trail. The scheduled sweep is disabled in
 * the shared fixture and driven explicitly here.
 */
class SyncTests extends AbstractGatewayTest {

    @Autowired
    private SyncService syncService;

    @Autowired
    private WebhookSigner signer;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Test
    @SVCs({"SVC_GW_0056"})
    void sync_mode_defaults_changes_and_never_bypasses_approval() throws Exception {
        String name = uniqueName("mode");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace =
                marketplaceRepository.register(name, upstream.toAbsolutePath().toString());
        assertThat(marketplace.syncMode()).isEqualTo(Marketplace.SYNC_ON_DEMAND);

        // Each valid mode is applied; the secret appears only for webhook and exactly once.
        String scheduled =
                changeMode(name, "scheduled").andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(scheduled, "$.marketplace.syncMode")).isEqualTo("scheduled");
        assertThat((Object) JsonPath.read(scheduled, "$.webhookSecret")).isNull();
        String webhook = changeMode(name, "webhook").andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(webhook, "$.webhookSecret");
        assertThat(secret).hasSize(64);

        // No read endpoint ever returns the secret; the mode is visible.
        String listed = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).contains("\"syncMode\":\"webhook\"").doesNotContain(secret);

        // An invalid mode is refused, an unknown marketplace is not found.
        changeModeExpecting(name, "push", status().isUnprocessableContent());
        changeModeExpecting(uniqueName("ghost"), "scheduled", status().isNotFound());

        // A sync-triggered ingestion lands held — never approved, never published.
        marketplaceRepository.updateSyncMode(name, Marketplace.SYNC_SCHEDULED, null);
        syncService.sweep(Integer.MAX_VALUE);
        List<Snapshot> snapshots = snapshotRepository.listByMarketplace(marketplace.id());
        assertThat(snapshots).isNotEmpty().allSatisfy(snapshot -> assertThat(snapshot.state())
                .isEqualTo(Snapshot.HELD));
    }

    @Test
    @SVCs({"SVC_GW_0057"})
    void scheduled_sweep_is_scoped_bounded_oldest_first_and_survives_a_failure() throws Exception {
        // The sweep is estate-wide, so this ordering-sensitive test first removes any scheduled
        // marketplaces earlier tests left behind.
        neutralizeScheduledMarketplaces();
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String url = upstream.toAbsolutePath().toString();
        Marketplace first = registerScheduled(uniqueName("sched-a"), url);
        Marketplace second = registerScheduled(uniqueName("sched-b"), url);
        Marketplace onDemand = marketplaceRepository.register(uniqueName("manual"), url);
        Marketplace broken = registerScheduled(
                uniqueName("sched-bad"), newWorkDir("missing").resolve("gone").toString());

        // Bounded and oldest-first: a batch of one takes the least recently attempted (both are
        // unattempted, so registration order), and the stamp rotates it behind the others.
        int firstPass = syncService.sweep(1);
        assertThat(firstPass).isEqualTo(1);
        assertThat(snapshotRepository.listByMarketplace(first.id())).hasSize(1);
        assertThat(snapshotRepository.listByMarketplace(second.id())).isEmpty();

        // The broken upstream fails without stopping the batch, and stays in the queue for later.
        int secondPass = syncService.sweep(3);
        assertThat(secondPass).isEqualTo(2);
        assertThat(snapshotRepository.listByMarketplace(second.id())).hasSize(1);
        assertThat(snapshotRepository.listByMarketplace(broken.id())).isEmpty();
        assertThat(marketplaceRepository.dueScheduledSync(Integer.MAX_VALUE))
                .extracting(Marketplace::id)
                .contains(broken.id());

        // Only scheduled marketplaces are ever polled.
        assertThat(snapshotRepository.listByMarketplace(onDemand.id())).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0058"})
    void webhook_endpoint_accepts_only_a_validly_signed_request_and_ignores_the_payload() throws Exception {
        String name = uniqueName("hook");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace =
                marketplaceRepository.register(name, upstream.toAbsolutePath().toString());
        String secret = enableWebhookMode(name);
        String expectedSha = headSha(upstream);

        // The payload names a foreign URL and ref; a valid signature still only ingests the
        // registered upstream's default branch.
        String payload =
                "{\"repository\":{\"clone_url\":\"https://evil.example/other.git\"},\"ref\":\"refs/heads/evil\"}";
        trigger(name, payload, signer.sign(secret, payload)).andExpect(status().isAccepted());
        Snapshot snapshot = awaitSnapshot(marketplace.id());
        assertThat(snapshot.sha()).isEqualTo(expectedSha);
        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);

        // Adversarial cases: each is rejected and none creates a snapshot.
        trigger(name, payload, null).andExpect(status().isForbidden());
        trigger(name, payload, "sha256=not-hex-not-right").andExpect(status().isForbidden());
        trigger(name, payload, signer.sign("0".repeat(64), payload)).andExpect(status().isForbidden());
        trigger(name, "{\"tampered\":true}", signer.sign(secret, payload)).andExpect(status().isForbidden());
        trigger(uniqueName("ghost"), payload, signer.sign(secret, payload)).andExpect(status().isNotFound());

        String onDemandName = uniqueName("plain");
        marketplaceRepository.register(onDemandName, upstream.toAbsolutePath().toString());
        trigger(onDemandName, payload, signer.sign(secret, payload)).andExpect(status().isNotFound());

        byte[] oversized = new byte[1024 * 1024 + 1];
        trigger(name, new String(oversized, StandardCharsets.ISO_8859_1), signer.sign(secret, oversized))
                .andExpect(status().isContentTooLarge());

        assertThat(snapshotRepository.listByMarketplace(marketplace.id())).hasSize(1);

        // Enabling webhook mode again rotates the secret: the old one stops working.
        String rotated = enableWebhookMode(name);
        assertThat(rotated).isNotEqualTo(secret);
        trigger(name, payload, signer.sign(secret, payload)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0059"})
    void a_failing_upstream_never_affects_what_the_facade_serves() throws Exception {
        String name = uniqueName("outage");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(name, upstream);
        approve(registered.snapshot().id());
        String approvedSha = registered.snapshot().sha();

        Path clone = newWorkDir("clone");
        assertThat(gitClone(facadeUrl(name, newPat()), clone).exitCode()).isZero();
        assertThat(headSha(clone)).isEqualTo(approvedSha);

        // Kill the upstream, then let both automated triggers attempt ingestion.
        String secret = enableWebhookMode(name);
        FileSystemUtils.deleteRecursively(upstream);
        trigger(name, "{}", signer.sign(secret, "{}")).andExpect(status().isAccepted());
        awaitSyncAttempt(name);
        marketplaceRepository.updateSyncMode(name, Marketplace.SYNC_SCHEDULED, null);
        syncService.sweep(Integer.MAX_VALUE);

        // Nothing changed: same single snapshot, same state, and the facade still serves it.
        assertThat(snapshotRepository.listByMarketplace(registered.marketplace().id()))
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.state()).isEqualTo(Snapshot.APPROVED));
        Path cloneAfter = newWorkDir("clone-after");
        assertThat(gitClone(facadeUrl(name, newPat()), cloneAfter).exitCode()).isZero();
        assertThat(headSha(cloneAfter)).isEqualTo(approvedSha);
    }

    @Test
    @SVCs({"SVC_GW_0060"})
    void mode_changes_and_sync_ingestions_are_on_the_ledger_with_their_trigger() throws Exception {
        String name = uniqueName("ledger");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace =
                marketplaceRepository.register(name, upstream.toAbsolutePath().toString());
        long subscriberId = subscriberRepository
                .create(uniqueName("sub"), "http://127.0.0.1:9/sink", "sub-secret", "snapshot.ingested")
                .id();
        String me = JsonPath.read(
                mockMvc.perform(get("/api/me").with(oidcLogin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.username");

        String secret = enableWebhookMode(name);
        trigger(name, "{}", signer.sign(secret, "{}")).andExpect(status().isAccepted());
        Snapshot fromWebhook = awaitSnapshot(marketplace.id());

        addUpstreamCommit(upstream, uniqueName("more"));
        marketplaceRepository.updateSyncMode(name, Marketplace.SYNC_SCHEDULED, null);
        syncService.sweep(Integer.MAX_VALUE);
        List<Snapshot> snapshots = snapshotRepository.listByMarketplace(marketplace.id());
        assertThat(snapshots).hasSize(2);

        List<Map<String, Object>> ledger = fetchLogRepository.list().stream()
                .filter(entry -> name.equals(entry.get("marketplace")))
                .toList();
        assertThat(ledger)
                .anySatisfy(entry -> {
                    assertThat(entry.get("event")).isEqualTo("sync-mode-changed");
                    assertThat(entry.get("principal")).isEqualTo(me);
                    assertThat(entry.get("detail")).isEqualTo("webhook");
                })
                .anySatisfy(entry -> {
                    assertThat(entry.get("event")).isEqualTo("snapshot-ingested");
                    assertThat(entry.get("principal")).isEqualTo(SyncService.WEBHOOK_ACTOR);
                    assertThat(entry.get("sha")).isEqualTo(fromWebhook.sha());
                })
                .anySatisfy(entry -> {
                    assertThat(entry.get("event")).isEqualTo("snapshot-ingested");
                    assertThat(entry.get("principal")).isEqualTo(SyncService.SCHEDULER_ACTOR);
                });

        // Each sync-triggered ingestion emitted the ordinary lifecycle event with its trigger actor.
        List<String> payloads = deliveryRepository.listBySubscriber(subscriberId).stream()
                .map(delivery -> delivery.payload())
                .filter(payload -> payload.contains("\"" + name + "\""))
                .toList();
        assertThat(payloads).hasSize(2);
        assertThat(payloads).anySatisfy(payload -> assertThat(payload).contains("\"actor\":\"webhook\""));
        assertThat(payloads).anySatisfy(payload -> assertThat(payload).contains("\"actor\":\"scheduler\""));
    }

    @Test
    @SVCs({"SVC_GW_0056"})
    void concurrent_ingests_of_one_marketplace_yield_one_snapshot() throws Exception {
        String name = uniqueName("race");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Marketplace marketplace =
                marketplaceRepository.register(name, upstream.toAbsolutePath().toString());

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        Callable<Snapshot> ingest = () -> {
            start.await();
            return ingestionService.ingest(marketplace, null);
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Snapshot>> futures =
                    List.of(pool.submit(ingest), pool.submit(ingest), pool.submit(ingest), pool.submit(ingest));
            start.countDown();
            for (Future<Snapshot> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).state()).isEqualTo(Snapshot.HELD);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(snapshotRepository.listByMarketplace(marketplace.id())).hasSize(1);
    }

    private Marketplace registerScheduled(String name, String url) {
        marketplaceRepository.register(name, url);
        return marketplaceRepository
                .updateSyncMode(name, Marketplace.SYNC_SCHEDULED, null)
                .orElseThrow();
    }

    private void neutralizeScheduledMarketplaces() {
        marketplaceRepository
                .dueScheduledSync(Integer.MAX_VALUE)
                .forEach(m -> marketplaceRepository.updateSyncMode(m.name(), Marketplace.SYNC_ON_DEMAND, null));
    }

    private org.springframework.test.web.servlet.ResultActions changeMode(String name, String mode) throws Exception {
        return mockMvc.perform(put("/api/marketplaces/%s/sync".formatted(name))
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"%s\"}".formatted(mode)))
                .andExpect(status().isOk());
    }

    private void changeModeExpecting(
            String name, String mode, org.springframework.test.web.servlet.ResultMatcher expectation) throws Exception {
        mockMvc.perform(put("/api/marketplaces/%s/sync".formatted(name))
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"%s\"}".formatted(mode)))
                .andExpect(expectation);
    }

    private String enableWebhookMode(String name) throws Exception {
        return JsonPath.read(
                changeMode(name, "webhook")
                        .andExpect(jsonPath("$.webhookSecret").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.webhookSecret");
    }

    private org.springframework.test.web.servlet.ResultActions trigger(
            String marketplace, String body, String signature) throws Exception {
        var request = post("/hooks/%s".formatted(marketplace))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.ISO_8859_1));
        if (signature != null) {
            request = request.header(InboundWebhookController.SIGNATURE_HEADER, signature);
        }
        return mockMvc.perform(request);
    }

    /** The webhook trigger ingests asynchronously; poll for the snapshot it promised. */
    private Snapshot awaitSnapshot(long marketplaceId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            List<Snapshot> snapshots = snapshotRepository.listByMarketplace(marketplaceId);
            if (!snapshots.isEmpty()) {
                return snapshots.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("queued webhook ingestion produced no snapshot within 15s");
    }

    /** A failed webhook ingest leaves no snapshot; its completion is visible as the attempt stamp. */
    private void awaitSyncAttempt(String name) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (marketplaceRepository
                    .findByName(name)
                    .map(m -> m.lastSyncAt() != null)
                    .orElse(false)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("queued webhook ingestion never completed within 15s");
    }
}
