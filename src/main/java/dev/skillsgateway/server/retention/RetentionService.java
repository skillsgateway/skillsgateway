package dev.skillsgateway.server.retention;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.persistence.StagingRefSightingRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.RefTransitions;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
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
 *
 * <p>Compaction also sweeps the publication staging references a crash left behind (GW_0168) —
 * the one thing retention does on the published side of the estate. It is here rather than in the
 * storage seam because deciding whether a staged commit is still anybody's snapshot takes the
 * database, and the seam is deliberately ignorant of it.
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
    private final StagingRefSightingRepository sightingRepository;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final SkillsGatewayProperties.Retention properties;

    public RetentionService(
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            StagingRefSightingRepository sightingRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            SkillsGatewayProperties properties) {
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.sightingRepository = sightingRepository;
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
                            marketplace.name(),
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
        touched.forEach(marketplace -> collectGarbage(GitStorage.Role.QUARANTINE, marketplace));
        // The other thing this pass reclaims, on the other side of the estate (GW_0168). Its own
        // failures are its own: a sweep that cannot read the published side must not turn a
        // completed purge into a failed compaction pass.
        try {
            sweepStagingRefs(actor);
        } catch (RuntimeException e) {
            log.warn("staging-reference sweep failed; the purge pass is unaffected", e);
        }
        return new PassResult(due.size(), purged);
    }

    /**
     * Removes publication staging references that no publication is going to finish, and reclaims
     * what they were holding (GW_0168).
     *
     * <p>Publication stages a snapshot's objects in the published repository under
     * {@code refs/staging/<sha>} and only then moves the served references, so that a transfer
     * which completes and a transition which is then refused leaves nothing on the wire. A process
     * killed between the two leaves the staging reference behind. It serves nothing — the facade
     * advertises {@code refs/heads/main} and {@code refs/snapshots/*} and nothing else — but it
     * keeps its objects reachable, so collection reclaims nothing and the repository carries a
     * whole snapshot that will never be served.
     *
     * <p>The sweep lives here and not in the storage seam because deciding this needs the database
     * and the seam is deliberately ignorant of it. Two conditions have to hold together, and the
     * second is the load-bearing one:
     *
     * <ol>
     *   <li><b>No live snapshot record names the commit.</b> Approval writes the approved row
     *       before it publishes, so a publication in flight always has one.
     *   <li><b>The reference has been under observation for longer than
     *       {@code staging-ref-max-age}.</b> Without this the sweep races a publication whose row
     *       it has not read yet: it would delete the staging reference and collect the objects out
     *       from under a transition that is about to point the served tip at them. The first
     *       condition alone is a database read taken at a different instant from the reference
     *       listing; this one is a bound that no publication can outrun.
     * </ol>
     *
     * <p>The two reads are also ordered — references first, records afterwards — so that a
     * publication which staged before the listing has necessarily committed its row before the
     * query. That ordering closes today's window on its own; the age bound is what keeps it closed
     * when the publication path changes.
     */
    @Requirements({"GW_0168"})
    public PassResult sweepStagingRefs(String actor) {
        if (!properties.stagingSweepEnabled()) {
            return new PassResult(0, 0);
        }
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.stagingRefMaxAge());
        Set<String> marketplaces;
        try {
            marketplaces = storage.marketplaces(GitStorage.Role.PUBLISHED);
        } catch (IOException e) {
            log.warn("staging-reference sweep skipped: the published repositories could not be listed", e);
            return new PassResult(0, 0);
        }
        int observed = 0;
        int swept = 0;
        for (String marketplace : marketplaces) {
            List<Ref> staged;
            try {
                staged = stagedRefs(marketplace);
            } catch (IOException e) {
                log.warn("staging-reference sweep of published repository '{}' skipped", marketplace, e);
                continue;
            }
            observed += staged.size();
            Map<String, Instant> firstSeen = sightingRepository.observe(marketplace, names(staged), now);
            List<String> abandoned = abandoned(marketplace, staged, firstSeen, staleBefore);
            if (abandoned.isEmpty()) {
                continue;
            }
            List<String> removed = remove(marketplace, abandoned);
            if (removed.isEmpty()) {
                continue;
            }
            swept += removed.size();
            sightingRepository.forget(marketplace, removed);
            collectGarbage(GitStorage.Role.PUBLISHED, marketplace);
            auditLogger.record(actor, marketplace, "staging-refs-swept:count=%d".formatted(removed.size()), null);
        }
        return new PassResult(observed, swept);
    }

    /** The staging namespace of one published repository, read before anything is asked of the database. */
    private List<Ref> stagedRefs(String marketplace) throws IOException {
        try (Repository repository = storage.published(marketplace)) {
            return repository.getRefDatabase().getRefsByPrefix(GitStorage.STAGING_REF_PREFIX);
        }
    }

    /**
     * The staged references this pass may remove: old enough, and pointing at a commit no live
     * snapshot record of this marketplace names.
     *
     * <p>The commit is taken from the reference's target rather than parsed out of its name. The
     * two agree in everything publication writes, but it is the target that pins the objects, and
     * a name is not evidence about what a reference points at.
     */
    private List<String> abandoned(
            String marketplace, List<Ref> staged, Map<String, Instant> firstSeen, Instant staleBefore) {
        Long marketplaceId = marketplaceRepository
                .findByName(marketplace)
                .map(Marketplace::id)
                .orElse(null);
        List<String> abandoned = new ArrayList<>();
        for (Ref ref : staged) {
            Instant seen = firstSeen.get(ref.getName());
            if (seen == null || seen.isAfter(staleBefore)) {
                continue;
            }
            ObjectId target = ref.getObjectId();
            if (target == null) {
                continue;
            }
            if (marketplaceId != null
                    && snapshotRepository
                            .findByMarketplaceAndSha(marketplaceId, target.name())
                            .isPresent()) {
                continue;
            }
            abandoned.add(ref.getName());
        }
        return abandoned;
    }

    /**
     * Deletes the references one at a time, answering with the ones that actually went. A refusal
     * is one reference's problem: the rest of the sweep is unrelated to it, and the next pass
     * retries the one that stayed because its sighting is still there.
     */
    private List<String> remove(String marketplace, List<String> refs) {
        List<String> removed = new ArrayList<>();
        try (Repository repository = storage.published(marketplace)) {
            for (String ref : refs) {
                try {
                    delete(repository, ref);
                    removed.add(ref);
                } catch (IOException e) {
                    log.warn("staging reference {} in published repository '{}' was not removed", ref, marketplace, e);
                }
            }
        } catch (IOException e) {
            log.warn("published repository '{}' could not be opened for the staging sweep", marketplace, e);
        }
        return removed;
    }

    private static List<String> names(List<Ref> refs) {
        return refs.stream().map(Ref::getName).toList();
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

    /**
     * Checked (GW_0136). The purge order is remove-the-pin, delete the row, then write the
     * {@code snapshot-purged} ledger entry — so a deletion that was refused and returned quietly
     * left the pin in place while the row went away. Nothing would ever revisit it: the content
     * would be retained forever, garbage collection would reclaim nothing, and the ledger would
     * assert a deletion that did not happen. Raising here stops the purge before the row is gone.
     */
    @Requirements({"GW_0136"})
    private static void delete(Repository repository, String ref) throws IOException {
        RefTransitions.delete(repository, ref);
    }

    /** Once per marketplace per pass; the expiry is now, so the objects go now, not in two weeks. */
    private void collectGarbage(GitStorage.Role role, String marketplace) {
        try (Repository repository = storage.open(role, marketplace);
                Git git = new Git(repository)) {
            git.gc().setExpire(Instant.now()).setPrunePreserved(true).call();
        } catch (IOException | GitAPIException e) {
            // The refs are gone, so the space is reclaimable; the next gc (this pass or JGit's own
            // auto-gc) will take it. Failing the pass here would strand later purges behind it.
            log.warn("garbage collection of {} repository '{}' failed", role.path(), marketplace, e);
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
