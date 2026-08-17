package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.catalog.CatalogService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Continuous re-vetting of approved content, and the retroactive quarantine it can trigger
 * (GW_0049-GW_0054).
 *
 * <p>Approval is a decision made against the evidence available on one day. This service exists
 * because that evidence goes stale: a connector gains a rule, a waiver lapses, an advisory lands.
 * It re-runs the chain over content that is already being served, and decides what the fresh answer
 * means for content teams already depend on.
 *
 * <p>Four properties are load-bearing.
 *
 * <ul>
 *   <li><b>Re-vetting never publishes.</b> Everything here either leaves publication alone or
 *       removes it. There is no path through this class that starts serving anything, which is what
 *       keeps {@code ApprovalService} the only publisher (ARCHITECTURE.md §5).
 *   <li><b>The effective outcome decides, not the recorded one.</b> A finding an active waiver
 *       covers is an accepted risk, and an accepted risk must not quarantine content — otherwise a
 *       waiver would clear the approval gate and then be ignored by the sweep that follows it.
 *   <li><b>An inconclusive run never retracts.</b> {@link RevetVerdict} draws that line and
 *       explains why.
 *   <li><b>Warn mode is genuinely inert.</b> It records and announces; nothing in it touches the
 *       published repository or the snapshot row. The only difference enforcement makes is whether
 *       {@link #quarantine} is called at all.
 * </ul>
 */
@Service
public class RevetService {

    private static final Logger log = LoggerFactory.getLogger(RevetService.class);

    /** Actor recorded for runs the scheduled sweep made rather than a person. */
    public static final String SWEEP_ACTOR = "revet-policy";

    /** Ledger events, so an auditor's grep terms are one list rather than scattered literals. */
    public static final String EVENT_VIOLATION = "revet-violation";

    public static final String EVENT_CLEAR = "revet-clear";
    public static final String EVENT_INCONCLUSIVE = "revet-inconclusive";
    public static final String EVENT_REVOKED = "snapshot-revoked";
    public static final String EVENT_UNPUBLISHED = "snapshot-unpublished";

    private final VettingService vettingService;
    private final WaiverService waiverService;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final FetchLogRepository fetchLogRepository;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final CatalogService catalogService;
    private final SkillsGatewayProperties.Revet properties;

    public RevetService(
            VettingService vettingService,
            WaiverService waiverService,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            FetchLogRepository fetchLogRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            CatalogService catalogService,
            SkillsGatewayProperties properties) {
        this.vettingService = vettingService;
        this.waiverService = waiverService;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.fetchLogRepository = fetchLogRepository;
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.catalogService = catalogService;
        this.properties = properties.vetting().revet();
    }

    @Schema(description = "What one re-vetting run concluded about one approved snapshot")
    public record RevetResult(
            @Schema(description = "Snapshot that was re-vetted")
            long snapshotId,

            @Schema(description = "Marketplace it belongs to")
            String marketplace,

            @Schema(description = "Commit SHA that was re-vetted")
            String sha,

            @Schema(description = "Chain run this re-vetting recorded")
            long runId,

            @Schema(description = "What the run means for already-approved content")
            RevetVerdict.Classification classification,

            @Schema(description = "The effective vetting outcome after active waivers")
            VettingChain.Outcome outcome,

            @Schema(description = "Whether the snapshot was revoked and unpublished as a result")
            boolean revoked,

            @Schema(
                    description = "Mode in force for this run: WARN records the violation and leaves publication"
                            + " alone, ENFORCE revokes",
                    allowableValues = {"WARN", "ENFORCE"})
            SkillsGatewayProperties.RevetMode mode,

            @Schema(description = "Blocking findings no active waiver covers; the reason for a violation")
            List<WaiverEvaluation.UncoveredFinding> uncovered,

            @Schema(description = "Identities that fetched this commit through the facade before the violation")
            List<FetchLogRepository.Fetcher> affected) {}

    /** What one sweep pass did. */
    @Schema(description = "Outcome of a re-vetting pass")
    public record PassResult(
            @Schema(description = "Snapshots re-vetted by this pass")
            int revetted,

            @Schema(description = "Of those, how many produced a violation")
            int violations,

            @Schema(description = "Of those violations, how many were enforced by revoking the snapshot")
            int revoked,

            @Schema(description = "Snapshots whose re-vetting run could not conclude")
            int inconclusive,

            @Schema(description = "The individual results, in the order they ran")
            List<RevetResult> results) {}

    /** The mode in force, so a caller can report it without reaching into the properties. */
    public SkillsGatewayProperties.RevetMode mode() {
        return properties.mode();
    }

    /**
     * The scheduled sweep (GW_0049): one bounded batch of the approved snapshots whose evidence is
     * oldest. Deliberately not "every approved snapshot every tick" — an estate of thousands would
     * make the sweep a periodic self-inflicted load spike, and re-vetting the same recently-vetted
     * snapshot again buys nothing. Oldest-first over a batch covers the estate in rotation, and
     * guarantees the interval any one snapshot waits is bounded by its size.
     */
    @Requirements({"GW_0049"})
    public PassResult sweep(String actor) {
        Instant cutoff = Instant.now().minus(properties.cadence());
        List<Snapshot> due = snapshotRepository.dueRevet(cutoff, properties.batchSize());
        List<RevetResult> results = new ArrayList<>(due.size());
        for (Snapshot snapshot : due) {
            try {
                results.add(revet(snapshot, VettingRepository.TRIGGER_REVET_SCHEDULED, actor));
            } catch (RuntimeException e) {
                // One snapshot's failure must not cost the rest of the batch their turn; the next
                // pass reaches it again, and it is still the oldest, so it is not skipped.
                log.warn("re-vetting of snapshot {} failed", snapshot.id(), e);
            }
        }
        return summarize(results);
    }

    /** Every live approved snapshot of one marketplace, re-vetted now (GW_0049). */
    @Requirements({"GW_0049"})
    public PassResult revetMarketplace(String name, String actor) {
        Marketplace marketplace = marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("marketplace '%s' not found".formatted(name)));
        List<RevetResult> results = new ArrayList<>();
        for (Snapshot snapshot : snapshotRepository.approvedByMarketplace(marketplace.id())) {
            results.add(revet(snapshot, VettingRepository.TRIGGER_REVET_MANUAL, actor));
        }
        return summarize(results);
    }

    /** One snapshot, re-vetted now (GW_0049). */
    @Requirements({"GW_0049"})
    public RevetResult revetSnapshot(long snapshotId, String actor) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        if (!Snapshot.APPROVED.equals(snapshot.state())) {
            // Re-vetting is about content that is being served. A held snapshot is vetted by the
            // approval surface, and a rejected or revoked one is not serving anything to retract.
            throw new IllegalStateException(
                    "snapshot %d is %s; only approved snapshots are re-vetted".formatted(snapshotId, snapshot.state()));
        }
        return revet(snapshot, VettingRepository.TRIGGER_REVET_MANUAL, actor);
    }

    /**
     * Re-vets one approved snapshot and acts on the answer (GW_0050, GW_0051, GW_0052, GW_0054).
     *
     * <p>The order is the safety property: the run is recorded first, the violation is written to
     * the ledger and announced second, and only then — and only in enforce mode — is anything
     * unpublished. A crash at any point leaves recorded evidence and served content, never
     * retracted content nobody can explain.
     */
    @Requirements({"GW_0050", "GW_0051", "GW_0052", "GW_0054"})
    private RevetResult revet(Snapshot snapshot, String trigger, String actor) {
        String marketplace = marketplaceName(snapshot);
        VettingService.Run run = vettingService.run(snapshot, marketplace, trigger);
        VettingRepository.Run recorded = vettingService.recordedRun(run.runId()).orElse(null);
        WaiverEvaluation.Effect effect = waiverService.evaluate(snapshot);
        RevetVerdict.Classification classification = RevetVerdict.classify(recorded, effect);

        List<FetchLogRepository.Fetcher> affected = List.of();
        boolean revoked = false;
        if (classification == RevetVerdict.Classification.VIOLATION) {
            affected = fetchLogRepository.fetchersOf(snapshot.sha());
            recordViolation(snapshot, marketplace, trigger, effect, affected, actor);
            if (properties.enforcing()) {
                revoked = quarantine(snapshot, marketplace, effect, actor);
            }
        } else if (classification == RevetVerdict.Classification.INCONCLUSIVE) {
            // Said out loud, because an estate that is silently not being re-vetted is
            // indistinguishable from one that is passing.
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_INCONCLUSIVE,
                    snapshot.sha(),
                    "trigger=%s; the chain could not conclude, so the snapshot stays approved; connectors=%s"
                            .formatted(trigger, effect.blockingConnectors()));
        } else {
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_CLEAR,
                    snapshot.sha(),
                    "trigger=%s; outcome=%s".formatted(trigger, effect.outcome().stored()));
        }
        return new RevetResult(
                snapshot.id(),
                marketplace,
                snapshot.sha(),
                run.runId(),
                classification,
                effect.outcome(),
                revoked,
                properties.mode(),
                effect.uncovered(),
                affected);
    }

    /**
     * The retroactive violation, in the ledger and on the wire (GW_0053, GW_0054) — and the whole
     * of what warn mode does. Every affected identity is named individually, so the blast radius is
     * answerable from the ledger alone rather than from a query someone has to think to run.
     */
    @Requirements({"GW_0053", "GW_0054"})
    private void recordViolation(
            Snapshot snapshot,
            String marketplace,
            String trigger,
            WaiverEvaluation.Effect effect,
            List<FetchLogRepository.Fetcher> affected,
            String actor) {
        String rules = effect.uncovered().stream()
                .map(WaiverEvaluation.UncoveredFinding::ruleId)
                .distinct()
                .toList()
                .toString();
        auditLogger.record(
                actor,
                marketplace,
                EVENT_VIOLATION,
                snapshot.sha(),
                "trigger=%s; mode=%s; connectors=%s; rules=%s; fetchedBy=%d"
                        .formatted(trigger, properties.mode(), effect.blockingConnectors(), rules, affected.size()));
        for (FetchLogRepository.Fetcher fetcher : affected) {
            auditLogger.record(
                    actor,
                    marketplace,
                    "revet-violation-affected",
                    snapshot.sha(),
                    "principal=%s; fetches=%d; lastFetch=%s"
                            .formatted(fetcher.principal(), fetcher.fetches(), fetcher.lastFetch()));
        }
        webhookService.emit(
                WebhookEvent.SNAPSHOT_REVET_VIOLATION,
                marketplace,
                snapshot.id(),
                snapshot.sha(),
                snapshot.state(),
                actor);
        log.warn(
                "re-vetting violation on approved snapshot {} ({} {}): {} — mode {}",
                snapshot.id(),
                marketplace,
                snapshot.sha(),
                effect.blockingConnectors(),
                properties.mode());
    }

    /**
     * Retroactive quarantine (GW_0050): move the snapshot to {@code revoked} and take its content
     * off the wire.
     *
     * <p>The database transition comes first and is conditional on the snapshot still being
     * approved, so two concurrent passes cannot both revoke it and cannot both unpublish. Only the
     * pass that won the transition touches git.
     *
     * <p>Nothing is re-ingested and quarantine is untouched: the content is still pinned at
     * {@code refs/snapshots/<sha>} in the quarantine repository, which is exactly what makes the
     * decision reviewable and reversible by a person.
     */
    @Requirements({"GW_0050", "GW_0054"})
    private boolean quarantine(Snapshot snapshot, String marketplace, WaiverEvaluation.Effect effect, String actor) {
        String violation = "re-vetting violation: %s".formatted(effect.blockingConnectors());
        Optional<Snapshot> revoked = snapshotRepository.revoke(snapshot.id(), actor, violation);
        if (revoked.isEmpty()) {
            log.info("snapshot {} was no longer approved when re-vetting tried to revoke it", snapshot.id());
            return false;
        }
        auditLogger.record(actor, marketplace, EVENT_REVOKED, snapshot.sha(), violation);
        try {
            boolean stoppedServing = storage.unpublish(marketplace, snapshot.sha());
            auditLogger.record(
                    actor,
                    marketplace,
                    EVENT_UNPUBLISHED,
                    snapshot.sha(),
                    stoppedServing
                            ? "refs/heads/main and refs/snapshots/<sha> removed; the marketplace serves nothing"
                            : "refs/snapshots/<sha> removed; a later approved snapshot still serves this marketplace");
        } catch (IOException e) {
            // The snapshot is revoked in the record but its refs may still be advertised: the
            // loudest possible failure, because it is the one case where the state and the wire
            // disagree. The next pass will not retry (the snapshot is no longer approved), so this
            // needs a person.
            log.error(
                    "snapshot {} ({} {}) was revoked but could not be unpublished; its refs may still be served",
                    snapshot.id(),
                    marketplace,
                    snapshot.sha(),
                    e);
            auditLogger.record(
                    actor,
                    marketplace,
                    "snapshot-unpublish-failed",
                    snapshot.sha(),
                    "revoked but still published: " + e.getMessage());
        }
        // The published set just shrank; the catalog re-derives so the retracted content leaves it
        // too (GW_0062). Never fails the revocation that triggered it.
        catalogService.rebuildQuietly();
        webhookService.emit(
                WebhookEvent.SNAPSHOT_REVOKED, marketplace, snapshot.id(), snapshot.sha(), Snapshot.REVOKED, actor);
        return true;
    }

    /** The identities that fetched a snapshot's content: the blast radius of a violation (GW_0053). */
    @Requirements({"GW_0053"})
    public List<FetchLogRepository.Fetcher> affected(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        return fetchLogRepository.fetchersOf(snapshot.sha());
    }

    private static PassResult summarize(List<RevetResult> results) {
        int violations = 0;
        int revoked = 0;
        int inconclusive = 0;
        for (RevetResult result : results) {
            if (result.classification() == RevetVerdict.Classification.VIOLATION) {
                violations++;
            }
            if (result.classification() == RevetVerdict.Classification.INCONCLUSIVE) {
                inconclusive++;
            }
            if (result.revoked()) {
                revoked++;
            }
        }
        return new PassResult(results.size(), violations, revoked, inconclusive, List.copyOf(results));
    }

    private String marketplaceName(Snapshot snapshot) {
        return marketplaceRepository
                .findById(snapshot.marketplaceId())
                .map(Marketplace::name)
                .orElseThrow(() ->
                        new IllegalStateException("marketplace %d not found".formatted(snapshot.marketplaceId())));
    }
}
