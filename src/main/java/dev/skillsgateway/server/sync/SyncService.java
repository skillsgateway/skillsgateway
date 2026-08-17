package dev.skillsgateway.server.sync;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The shared trigger path for sync-driven ingestion (GW_0056–GW_0060). Both automated triggers —
 * the polling sweep and the inbound webhook — land here, so every automated ingestion stamps the
 * attempt, writes the same ledger entry shape, and emits the same lifecycle event. Nothing in this
 * class touches approval or publication: a sync-triggered snapshot is held (or rejected by the
 * manifest policy) exactly like a manual one.
 */
@Service
public class SyncService {

    /** Ledger actor for the polling sweep, mirroring retention's policy actor convention. */
    public static final String SCHEDULER_ACTOR = "scheduler";

    /** Ledger actor for a forge webhook trigger. */
    public static final String WEBHOOK_ACTOR = "webhook";

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final MarketplaceRepository marketplaceRepository;
    private final IngestionService ingestionService;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final ExecutorService webhookExecutor;
    private final SecureRandom secureRandom = new SecureRandom();

    public SyncService(
            MarketplaceRepository marketplaceRepository,
            IngestionService ingestionService,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            ExecutorService syncWebhookExecutor) {
        this.marketplaceRepository = marketplaceRepository;
        this.ingestionService = ingestionService;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.webhookExecutor = syncWebhookExecutor;
    }

    /**
     * Changes the sync mode and manages the webhook secret with it: entering webhook mode
     * (re-)generates the secret — which is also the rotation mechanism — and leaving it discards
     * the key. The secret is returned exactly once, here; no read path ever exposes it.
     */
    @Requirements({"GW_0056", "GW_0060"})
    public Optional<ModeChange> changeMode(String name, String mode, String actor) {
        String secret = Marketplace.SYNC_WEBHOOK.equals(mode) ? newSecret() : null;
        Optional<Marketplace> updated = marketplaceRepository.updateSyncMode(name, mode, secret);
        updated.ifPresent(
                marketplace -> auditLogger.record(actor, marketplace.name(), "sync-mode-changed", null, mode));
        return updated.map(marketplace -> new ModeChange(marketplace, secret));
    }

    /** The updated marketplace and, only when the new mode is webhook, its just-generated secret. */
    public record ModeChange(Marketplace marketplace, String webhookSecret) {}

    /**
     * One polling sweep pass (GW_0057): the least recently attempted scheduled marketplaces, up to
     * the batch bound. Every attempt is stamped, success or failure, so a dead upstream rotates to
     * the back of the queue instead of monopolizing it; one failure never stops the batch.
     */
    @Requirements({"GW_0057"})
    public int sweep(int batchSize) {
        List<Marketplace> due = marketplaceRepository.dueScheduledSync(batchSize);
        int ingested = 0;
        for (Marketplace marketplace : due) {
            if (ingest(marketplace, SCHEDULER_ACTOR) != null) {
                ingested++;
            }
        }
        return ingested;
    }

    /**
     * Queues the ingestion a validly signed webhook request asked for (GW_0058). Asynchronous so
     * the forge gets its 202 before the upstream fetch runs, single-threaded so queued triggers
     * are serialized; the returned future exists for tests and is ignored by the controller.
     */
    @Requirements({"GW_0058"})
    public CompletableFuture<Snapshot> queueWebhookIngest(Marketplace marketplace) {
        return CompletableFuture.supplyAsync(() -> ingest(marketplace, WEBHOOK_ACTOR), webhookExecutor);
    }

    /**
     * The one automated trigger path: ingest, stamp the attempt, audit with the trigger identity,
     * emit the ordinary lifecycle event (GW_0059, GW_0060). A failure — typically an unreachable
     * upstream — is logged and swallowed: it changes no snapshot state and no published ref, and
     * the facade keeps serving the last approved snapshot untouched.
     */
    @Requirements({"GW_0059", "GW_0060"})
    private Snapshot ingest(Marketplace marketplace, String actor) {
        try {
            Snapshot snapshot = ingestionService.ingest(marketplace);
            auditLogger.record(actor, marketplace.name(), "snapshot-ingested", snapshot.sha());
            webhookService.emit(
                    WebhookEvent.SNAPSHOT_INGESTED,
                    marketplace.name(),
                    snapshot.id(),
                    snapshot.sha(),
                    snapshot.state(),
                    actor);
            return snapshot;
        } catch (RuntimeException e) {
            log.warn("{} sync of marketplace '{}' failed; retrying later", actor, marketplace.name(), e);
            return null;
        } finally {
            marketplaceRepository.stampSyncAttempt(marketplace.id());
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
