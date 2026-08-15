package io.github.jimisola.skillsgateway.retention;

import io.github.jimisola.skillsgateway.admin.AdminAuditLogger;
import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.webhook.WebhookEvent;
import io.github.jimisola.skillsgateway.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Snapshot retention: policy evaluation (GW_0031), soft deletion with a restore window (GW_0032),
 * the guard that keeps approved — that is, served — snapshots out of reach (GW_0033), and the
 * compaction that permanently removes an expired soft deletion together with its git storage
 * (GW_0034). Every action is written to the append-only ledger (GW_0035).
 *
 * <p>Evaluation and compaction are deliberately separate passes: the restore window only means
 * something if a wrong criterion costs a reversible mark rather than content.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** Actor recorded for deletions made by the scheduled policy pass rather than by a person. */
    public static final String POLICY_ACTOR = "retention-policy";

    /** Reason recorded when an administrator deletes a snapshot by hand. */
    public static final String MANUAL_REASON = "manual";

    private static final String SNAPSHOT_REF_PREFIX = "refs/snapshots/";
    private static final String INCOMING_REF = "refs/quarantine/incoming";
    private static final String NO_MARKETPLACE = "-";

    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final SkillsGatewayProperties.Retention properties;

    public RetentionService(
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            SkillsGatewayProperties properties) {
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.properties = properties.retention();
    }

    @Schema(description = "A snapshot a retention policy would delete, and the criterion that selected it")
    public record Candidate(
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(description = "Marketplace the snapshot belongs to")
            String marketplace,

            @Schema(description = "Upstream commit SHA the snapshot is pinned to")
            String sha,

            @Schema(description = "Vetting state; never approved, which is categorically ineligible")
            String state,

            @Schema(
                    description = "Criterion that selected the snapshot",
                    allowableValues = {"held-too-long", "superseded"})
            String reason,

            @Schema(description = "Ingestion time") Instant createdAt) {}

    /** What one retention pass did. */
    @Schema(description = "Outcome of a retention pass")
    public record PassResult(
            @Schema(description = "Snapshots evaluated as eligible")
            int selected,

            @Schema(description = "Snapshots acted on by this pass")
            int acted) {}

    /**
     * The snapshots the policies in force would delete right now, across every marketplace — a dry
     * run that writes nothing, so an operator can inspect a policy before enabling it.
     */
    public List<Candidate> candidates() {
        return candidates(null);
    }

    /** As {@link #candidates()}, restricted to one marketplace when {@code only} is non-null. */
    @Requirements({"GW_0031", "GW_0033"})
    public List<Candidate> candidates(String only) {
        Instant now = Instant.now();
        List<Candidate> candidates = new ArrayList<>();
        for (Marketplace marketplace : marketplaceRepository.list()) {
            if (only != null && !only.equals(marketplace.name())) {
                continue;
            }
            SkillsGatewayProperties.Retention.Policy policy = properties.policyFor(marketplace.name());
            snapshotRepository
                    .candidates(
                            marketplace.id(),
                            policy.heldCriterionEnabled(),
                            now.minus(policy.heldMaxAge()),
                            policy.supersededCriterionEnabled(),
                            now.minus(policy.supersededMinAge()),
                            now.minus(policy.minIdle()),
                            properties.batchSize())
                    .forEach(candidate -> candidates.add(new Candidate(
                            candidate.snapshot().id(),
                            marketplace.name(),
                            candidate.snapshot().sha(),
                            candidate.snapshot().state(),
                            candidate.reason(),
                            candidate.snapshot().createdAt())));
        }
        return candidates;
    }

    /**
     * Evaluates every marketplace against its policy and soft-deletes what it selects. The pass
     * outcome and each deletion land in the ledger; the deletions are reversible for the length of
     * the marketplace's restore window.
     */
    public PassResult evaluate(String actor) {
        return evaluate(actor, null);
    }

    /** As {@link #evaluate(String)}, restricted to one marketplace when {@code only} is non-null. */
    @Requirements({"GW_0031", "GW_0032", "GW_0035"})
    public PassResult evaluate(String actor, String only) {
        List<Candidate> selected = candidates(only);
        int acted = 0;
        for (Candidate candidate : selected) {
            SkillsGatewayProperties.Retention.Policy policy = properties.policyFor(candidate.marketplace());
            Optional<Snapshot> deleted = snapshotRepository.softDelete(
                    candidate.snapshotId(), candidate.reason(), Instant.now().plus(policy.restoreWindow()));
            if (deleted.isEmpty()) {
                continue;
            }
            acted++;
            recordDeletion(deleted.get(), candidate.marketplace(), candidate.reason(), actor);
        }
        auditLogger.record(
                actor,
                only == null ? NO_MARKETPLACE : only,
                "retention-evaluated:selected=%d,deleted=%d".formatted(selected.size(), acted),
                null);
        return new PassResult(selected.size(), acted);
    }

    /**
     * An administrator's own deletion. The approved guard is the repository's, not this method's:
     * the {@code UPDATE} itself excludes approved snapshots, so served content stays served
     * whatever a caller asks for.
     */
    @Requirements({"GW_0032", "GW_0033", "GW_0035"})
    public Snapshot softDelete(long snapshotId, String reason, String actor) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        if (Snapshot.APPROVED.equals(snapshot.state())) {
            throw new IllegalStateException(
                    "snapshot %d is approved and is served by the facade; it cannot be deleted".formatted(snapshotId));
        }
        if (snapshot.deleted()) {
            throw new IllegalStateException("snapshot %d is already deleted".formatted(snapshotId));
        }
        SkillsGatewayProperties.Retention.Policy policy = properties.policyFor(marketplaceName(snapshot));
        Snapshot deleted = snapshotRepository
                .softDelete(snapshotId, reason, Instant.now().plus(policy.restoreWindow()))
                .orElseThrow(() -> new IllegalStateException("snapshot %d cannot be deleted".formatted(snapshotId)));
        recordDeletion(deleted, marketplaceName(deleted), reason, actor);
        return deleted;
    }

    /** Restores a soft-deleted snapshot; only possible while compaction has not reached it. */
    @Requirements({"GW_0032", "GW_0035"})
    public Snapshot restore(long snapshotId, String actor) {
        snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Snapshot restored = snapshotRepository
                .restore(snapshotId)
                .orElseThrow(() -> new IllegalStateException("snapshot %d is not deleted".formatted(snapshotId)));
        String marketplace = marketplaceName(restored);
        auditLogger.record(actor, marketplace, "snapshot-restored", restored.sha());
        webhookService.emit(
                WebhookEvent.SNAPSHOT_RESTORED, marketplace, restored.id(), restored.sha(), restored.state(), actor);
        return restored;
    }

    /**
     * Permanently removes every soft-deleted snapshot whose restore window has elapsed: the pinned
     * quarantine ref goes with JGit, the repository is garbage-collected once per affected
     * marketplace so the objects the deleted tip made unreachable are reclaimed, and the record is
     * deleted. What the snapshot was, and that it was removed, stays in the append-only ledger.
     */
    @Requirements({"GW_0034", "GW_0035"})
    public PassResult compact(String actor) {
        List<Snapshot> due = snapshotRepository.duePurge(Instant.now(), properties.batchSize());
        Set<String> touched = new LinkedHashSet<>();
        Map<Long, String> names = new HashMap<>();
        int purged = 0;
        for (Snapshot snapshot : due) {
            String marketplace = names.computeIfAbsent(snapshot.marketplaceId(), id -> marketplaceName(snapshot));
            try {
                removePin(marketplace, snapshot.sha());
            } catch (IOException e) {
                // The record stays; the next pass retries. Deleting it while the ref survived would
                // strand the objects with nothing left pointing at what they were.
                log.warn("purge of snapshot {} skipped: quarantine ref removal failed", snapshot.id(), e);
                continue;
            }
            if (!snapshotRepository.purge(snapshot.id())) {
                continue;
            }
            purged++;
            touched.add(marketplace);
            auditLogger.record(actor, marketplace, "snapshot-purged", snapshot.sha());
        }
        touched.forEach(this::collectGarbage);
        return new PassResult(due.size(), purged);
    }

    /** Deletes {@code refs/snapshots/<sha>}, and the staging ref when it still points at the same commit. */
    private void removePin(String marketplace, String sha) throws IOException {
        try (Repository repository = storage.quarantine(marketplace)) {
            delete(repository, SNAPSHOT_REF_PREFIX + sha);
            Ref incoming = repository.exactRef(INCOMING_REF);
            // The staging ref is force-updated by the next ingestion anyway; leaving it pointing at
            // a purged commit would keep the whole history reachable and reclaim nothing.
            if (incoming != null && ObjectId.fromString(sha).equals(incoming.getObjectId())) {
                delete(repository, INCOMING_REF);
            }
        }
    }

    private static void delete(Repository repository, String ref) throws IOException {
        if (repository.exactRef(ref) == null) {
            return;
        }
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        update.delete();
    }

    /** Once per marketplace per pass; the expiry is now, so the objects go now, not in two weeks. */
    private void collectGarbage(String marketplace) {
        try (Repository repository = storage.quarantine(marketplace);
                Git git = new Git(repository)) {
            git.gc().setExpire(Instant.now()).setPrunePreserved(true).call();
        } catch (IOException | GitAPIException e) {
            // The refs are gone, so the space is reclaimable; the next gc (this pass or JGit's own
            // auto-gc) will take it. Failing the pass here would strand later purges behind it.
            log.warn("garbage collection of quarantine repository '{}' failed", marketplace, e);
        }
    }

    private void recordDeletion(Snapshot snapshot, String marketplace, String reason, String actor) {
        auditLogger.record(actor, marketplace, "snapshot-soft-deleted:" + reason, snapshot.sha());
        webhookService.emit(
                WebhookEvent.SNAPSHOT_SOFT_DELETED,
                marketplace,
                snapshot.id(),
                snapshot.sha(),
                snapshot.state(),
                actor);
    }

    private String marketplaceName(Snapshot snapshot) {
        return marketplaceRepository
                .findById(snapshot.marketplaceId())
                .map(Marketplace::name)
                .orElse(NO_MARKETPLACE);
    }
}
