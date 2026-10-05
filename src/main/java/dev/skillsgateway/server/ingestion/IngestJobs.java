package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.MarketplaceRepository.IngestRecord;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The on-demand ingest as a job (GW_INGEST_0066): claim the marketplace, answer at once, run the
 * ingestion on a gateway thread. The automated triggers keep their own paths and only share the
 * stage reporting, which lives in {@link IngestionService}.
 */
@Service
public class IngestJobs {

    private static final Logger log = LoggerFactory.getLogger(IngestJobs.class);

    /** Two, so one slow first ingest leaves room for another marketplace; the rest wait visibly queued. */
    private static final int WORKERS = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IngestionService ingestionService;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final ExecutorService executor;

    public IngestJobs(
            IngestionService ingestionService,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger,
            WebhookService webhookService) {
        this.ingestionService = ingestionService;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(WORKERS, runnable -> {
            Thread thread = new Thread(runnable, "ingest-job-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts an ingest of the marketplace unless one is already running anywhere, and answers with
     * the status either way: a second request follows the ingest in progress instead of queueing
     * another (GW_INGEST_0066). A running ingest whose heartbeat lapsed is claimed over (GW_INGEST_0067).
     */
    @Requirements({"GW_INGEST_0066", "GW_INGEST_0067"})
    public IngestStatus start(Marketplace marketplace, String actor) {
        UUID attempt = UUID.randomUUID();
        if (marketplaceRepository.claimIngest(marketplace.id(), attempt, IngestionService.HEARTBEAT_STALE)) {
            ingestionService.own(attempt);
            try {
                executor.execute(() -> run(marketplace, actor, attempt));
            } catch (RuntimeException e) {
                ingestionService.disown(attempt);
                throw e;
            }
        }
        return status(marketplace.name()).orElseThrow();
    }

    private void run(Marketplace marketplace, String actor, UUID attempt) {
        try {
            Snapshot snapshot = ingestionService.ingest(marketplace, actor, attempt);
            auditLogger.record(actor, marketplace.name(), "snapshot-ingested", snapshot.sha());
            webhookService.emit(
                    WebhookEvent.SNAPSHOT_INGESTED,
                    marketplace.name(),
                    snapshot.id(),
                    snapshot.sha(),
                    snapshot.state(),
                    actor);
        } catch (RuntimeException e) {
            // Already recorded on the marketplace, in the ledger and in the log by IngestionService.
            log.debug("on-demand ingest of marketplace '{}' failed", marketplace.name(), e);
        } finally {
            ingestionService.disown(attempt);
        }
    }

    /** The marketplace's running and last finished ingest; empty for an unknown marketplace. */
    @Requirements({"GW_INGEST_0066", "GW_INGEST_0067"})
    public Optional<IngestStatus> status(String name) {
        return marketplaceRepository
                .ingestRecord(name, IngestionService.HEARTBEAT_STALE)
                .map(IngestJobs::toStatus);
    }

    private static IngestStatus toStatus(IngestRecord row) {
        IngestStatus.Running running = row.ingestAttempt() == null
                ? null
                : new IngestStatus.Running(
                        row.ingestStage(), row.ingestStartedAt(), Boolean.TRUE.equals(row.interrupted()));
        IngestStatus.Last last = row.lastIngestOutcome() == null
                ? null
                : new IngestStatus.Last(
                        row.lastIngestAt(),
                        row.lastIngestOutcome(),
                        row.lastIngestSnapshotId(),
                        row.lastIngestSnapshotState(),
                        row.lastIngestReason(),
                        failure(row.lastIngestFailure()));
        return new IngestStatus(row.name(), running, last);
    }

    private static UpstreamFailure failure(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, UpstreamFailure.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void stop() {
        // Claimed attempts stop heartbeating and read as interrupted, which is what they are.
        executor.shutdownNow();
    }
}
