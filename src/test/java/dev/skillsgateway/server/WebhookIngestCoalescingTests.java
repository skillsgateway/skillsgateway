package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.sync.SyncService;
import io.github.reqstool.annotations.SVCs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Webhook triggers for one marketplace coalesce into at most one queued ingest (GW_INGEST_0076), so
 * however many arrive while the queue is busy, each marketplace waits behind at most one ingest of
 * every other.
 */
class WebhookIngestCoalescingTests extends AbstractGatewayTest {

    @Autowired
    private SyncService syncService;

    @Autowired
    @Qualifier("syncWebhookExecutor")
    private ExecutorService webhookExecutor;

    private long ingested(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> "snapshot-ingested".equals(entry.get("event")))
                .filter(entry -> marketplace.equals(entry.get("marketplace")))
                .count();
    }

    private Marketplace registered(String prefix) throws Exception {
        String name = uniqueName(prefix);
        registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        return marketplaceRepository.findByName(name).orElseThrow();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0076"})
    void triggers_queued_while_the_queue_is_busy_ingest_once_and_others_still_run() throws Exception {
        Marketplace alpha = registered("alpha");
        Marketplace beta = registered("beta");
        long alphaBefore = ingested(alpha.name());
        long betaBefore = ingested(beta.name());
        CountDownLatch release = new CountDownLatch(1);
        Future<?> busy = webhookExecutor.submit(() -> {
            release.await();
            return null;
        });

        List<CompletableFuture<Snapshot>> alphas = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            alphas.add(syncService.queueWebhookIngest(alpha));
        }
        CompletableFuture<Snapshot> betaRun = syncService.queueWebhookIngest(beta);
        release.countDown();
        busy.get(10, TimeUnit.SECONDS);
        CompletableFuture.allOf(alphas.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertThat(betaRun.get(30, TimeUnit.SECONDS)).as("beta's ingest").isNotNull();

        assertThat(alphas)
                .as("every alpha trigger answered by one ingest")
                .allSatisfy(run -> assertThat(run).isSameAs(alphas.getFirst()));
        assertThat(ingested(alpha.name())).as("alpha's ingests").isEqualTo(alphaBefore + 1);
        assertThat(ingested(beta.name())).as("beta's ingests").isEqualTo(betaBefore + 1);

        // Once that ingest has started, a new trigger queues a new one: a later push is never lost.
        CompletableFuture<Snapshot> later = syncService.queueWebhookIngest(alpha);
        assertThat(later).isNotSameAs(alphas.getFirst());
        later.get(30, TimeUnit.SECONDS);
        assertThat(ingested(alpha.name())).isEqualTo(alphaBefore + 2);
    }
}
