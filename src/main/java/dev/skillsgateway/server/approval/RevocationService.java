package dev.skillsgateway.server.approval;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.ServedContentChangedEvent;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Administrative revocation: an administrator withdraws an approved snapshot on knowledge the
 * vetting chain does not hold (GW_APPROVAL_0015).
 *
 * <p>A sibling of {@link ApprovalService} rather than a method on it. The two share a storage seam
 * and the repair discipline below, and none of the gates: approval's checks guard the direction
 * that publishes, and folding an act with the opposite preconditions into {@code doApprove} would
 * leave a reader unable to tell which check guards which direction.
 *
 * <p><b>One identity revokes; two are needed to put it back.</b> {@link ApprovalService} enforces
 * separation of duties on approval (GW_APPROVAL_0010) because publishing is the direction that can do
 * harm. Revocation only ever withdraws, so a second reviewer buys no safety here and costs time
 * during an active incident — the mandatory reason and the ledger entry are the accountability.
 * Reversing a revocation does publish, so it takes a second administrator; that half lives in
 * {@link ApprovalService} because a reversal is an approval carrying an acknowledgement, not an act
 * of its own (GW_APPROVAL_0017). This asymmetry is deliberate and is not an oversight to be tidied
 * away.
 *
 * <p>The ordering below is {@link dev.skillsgateway.server.vetting.RevetService}'s, inherited whole:
 * the conditional state transition first so that two concurrent revocations cannot both revoke and
 * cannot both unpublish, quarantine left untouched so the decision stays reviewable, and a failure
 * between the record and the wire raised loudly rather than retried.
 */
@Service
public class RevocationService {

    private static final Logger log = LoggerFactory.getLogger(RevocationService.class);

    /** Ledger event for the withdrawal itself. */
    public static final String EVENT_REVOKED = "snapshot-revoked";

    /** Ledger event for what the marketplace serves once the withdrawal has landed. */
    public static final String EVENT_UNPUBLISHED = "snapshot-unpublished";

    /**
     * Ledger event for a return to an earlier approved snapshot. Its own event rather than a detail
     * on the revocation, because it is a publication: the ledger records acts, not one act with a
     * hidden second effect (GW_APPROVAL_0016).
     */
    public static final String EVENT_ROLLED_BACK = "snapshot-rolled-back";

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final CatalogService catalogService;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final ApplicationEventPublisher events;

    public RevocationService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            CatalogService catalogService,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            ApplicationEventPublisher events) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.catalogService = catalogService;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.events = events;
    }

    /**
     * What the marketplace serves once the snapshot is withdrawn. There is no default value, and
     * that is the point (GW_APPROVAL_0016): neither outcome is safe to assume. Returning to the
     * previous snapshot republishes older content that may carry the same compromise, since a
     * poisoned transitive dependency is usually present there too; serving nothing takes down a
     * marketplace whose predecessor may be obviously clean. Only the person holding the reason
     * knows which, so a request that states neither is refused.
     */
    public enum ServeAfter {
        /** Return to the marketplace's previous approved snapshot. */
        PREVIOUS_APPROVED,
        /** Leave the marketplace serving nothing. */
        NOTHING;

        /** Wire form: the lower-case name, so the published vocabulary matches every other enum's. */
        @JsonValue
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The outcome of a withdrawal.
     *
     * @param snapshot the snapshot as revoked
     * @param stoppedServing whether the marketplace was left serving nothing by the unpublish, before
     *     any return to an earlier snapshot
     * @param rolledBackTo the snapshot now served, or empty when the marketplace serves nothing
     */
    @Schema(description = "The result of withdrawing an approved snapshot")
    public record Revoked(Snapshot snapshot, boolean stoppedServing, Optional<Snapshot> rolledBackTo) {}

    /**
     * Withdraw an approved snapshot (GW_APPROVAL_0015, GW_APPROVAL_0016).
     *
     * <p>Whatever the vetting chain would currently conclude, and whatever mode automated re-vetting
     * is configured in: that mode governs what re-vetting does with its own findings and has no
     * bearing on a person's decision, and a chain that would clear the content is exactly the
     * situation being overridden.
     *
     * @throws SnapshotNotFoundException if no such snapshot exists
     * @throws MissingRevocationReasonException if the reason is absent or blank
     * @throws RollbackUnavailableException if a return was asked for and there is nothing to return
     *     to — raised <b>before</b> anything is withdrawn, so a request that cannot be honoured
     *     changes nothing rather than delivering the outcome the caller did not choose
     * @throws NotApprovedException if the snapshot is not approved, including when a concurrent
     *     revocation reached it first
     */
    @Requirements({"GW_APPROVAL_0015", "GW_APPROVAL_0016"})
    public Revoked revoke(long snapshotId, String reason, ServeAfter serveAfter, String administrator) {
        if (reason == null || reason.isBlank()) {
            throw new MissingRevocationReasonException(snapshotId);
        }
        Snapshot current =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        if (!Snapshot.APPROVED.equals(current.state())) {
            throw new NotApprovedException(snapshotId, current.state());
        }
        Marketplace marketplace = marketplaceRepository
                .findById(current.marketplaceId())
                .orElseThrow(
                        () -> new ApprovalException("marketplace %d not found".formatted(current.marketplaceId())));

        // Before the transition, so an impossible request withdraws nothing (GW_APPROVAL_0016).
        Optional<Snapshot> target = serveAfter == ServeAfter.PREVIOUS_APPROVED
                ? snapshotRepository.previousApproved(current.marketplaceId(), snapshotId)
                : Optional.empty();
        if (serveAfter == ServeAfter.PREVIOUS_APPROVED && target.isEmpty()) {
            throw new RollbackUnavailableException(snapshotId, marketplace.name());
        }

        // The predicate lives in the statement, so a concurrent revocation or re-vetting pass cannot
        // revoke the same snapshot twice, and only the caller that won the transition touches git.
        Snapshot revoked = snapshotRepository
                .revoke(snapshotId, administrator, reason, Snapshot.REVOKED_ADMINISTRATIVELY)
                .orElseThrow(() -> new NotApprovedException(snapshotId, Snapshot.REVOKED));
        auditLogger.record(administrator, marketplace.name(), EVENT_REVOKED, revoked.sha(), reason);

        boolean stoppedServing = false;
        try {
            stoppedServing = storage.unpublish(marketplace.name(), revoked.sha());
            auditLogger.record(
                    administrator,
                    marketplace.name(),
                    EVENT_UNPUBLISHED,
                    revoked.sha(),
                    stoppedServing
                            ? "refs/heads/main and refs/snapshots/<sha> removed; the marketplace serves nothing"
                            : "refs/snapshots/<sha> removed; a later approved snapshot still serves this marketplace");
        } catch (IOException | RuntimeException unpublishFailed) {
            // The snapshot is withdrawn in the record but its references may still be advertised:
            // the one case where the record and the wire disagree, and the loudest thing this class
            // can do. Nothing retries — no later pass picks up a snapshot that is no longer
            // approved — so this needs a person. The announcement below still goes out, because the
            // mirror reconciles against what is served now and will repair what this failure left.
            log.error(
                    "snapshot {} ({} {}) was revoked but could not be unpublished; its refs may still be served",
                    snapshotId,
                    marketplace.name(),
                    revoked.sha(),
                    unpublishFailed);
        }

        Optional<Snapshot> served = Optional.empty();
        if (target.isPresent()) {
            served = republish(marketplace, target.get(), revoked, administrator);
        }

        catalogService.rebuildQuietly();
        events.publishEvent(new ServedContentChangedEvent(marketplace.name(), EVENT_REVOKED));
        webhookService.emit(
                WebhookEvent.SNAPSHOT_REVOKED,
                marketplace.name(),
                revoked.id(),
                revoked.sha(),
                Snapshot.REVOKED,
                administrator);
        return new Revoked(revoked, stoppedServing, served);
    }

    /**
     * Return the marketplace to an earlier approved snapshot, after the withdrawal has landed.
     *
     * <p>Two operations rather than one atomic swap of the served reference: the storage seam has no
     * compare-and-swap across two SHAs, and inventing one for this would be new capability for a
     * window bounded by two local git operations — during which a client sees exactly the
     * serve-nothing state the system must already handle.
     *
     * <p>The target keeps its approval. Nothing is re-decided, no gate runs again, and
     * {@code decided_by} is untouched: this republishes a decision that was already made, and
     * recording it as a fresh approval would invent a reviewer who did not act.
     */
    @Requirements({"GW_APPROVAL_0016"})
    private Optional<Snapshot> republish(
            Marketplace marketplace, Snapshot target, Snapshot revoked, String administrator) {
        try {
            storage.publish(marketplace.name(), target.sha());
            auditLogger.record(
                    administrator,
                    marketplace.name(),
                    EVENT_ROLLED_BACK,
                    target.sha(),
                    "returned to the previous approved snapshot after revoking " + revoked.sha());
            return Optional.of(target);
        } catch (IOException | RuntimeException publishFailed) {
            // The withdrawal stands — that is the part that mattered and it has landed. What failed
            // is the convenience of putting the previous snapshot back, so the marketplace serves
            // nothing, which is the other outcome the caller could have chosen. Reported loudly and
            // not retried: publishing is idempotent by SHA, so a person can simply run it again.
            log.error(
                    "snapshot {} ({} {}) was revoked, but returning {} to service failed;"
                            + " the marketplace now serves nothing",
                    revoked.id(),
                    marketplace.name(),
                    revoked.sha(),
                    target.sha(),
                    publishFailed);
            return Optional.empty();
        }
    }
}
