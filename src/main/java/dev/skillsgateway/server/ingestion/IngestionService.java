package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.policy.SnapshotFactsService;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.RefTransitions;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Ledger event for an ingest attempt that failed, whatever triggered it (GW_AUDIT_0010). */
    public static final String EVENT_INGEST_FAILED = "ingest-failed";

    private static final String INCOMING_REF = "refs/quarantine/incoming";
    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final VettingService vettingService;
    private final ManifestPolicy manifestPolicy;
    private final ExternalSourceResolver externalSourceResolver;
    private final ManifestRewriter manifestRewriter;
    private final SnapshotFactsService factsService;

    /**
     * One lock per marketplace: with sync modes (GW_INGEST_0011, GW_INGEST_0012) a manual ingest, a scheduler
     * tick and a webhook trigger can arrive together, and a concurrent same-marketplace ingest
     * races both JGit's ref lockfile on the incoming ref and the exists-check-then-insert against
     * the (marketplace_id, sha) unique constraint. Serializing at this one choke point covers
     * every trigger path. Entries are never removed; the estate of marketplaces is small and an
     * eviction scheme would reintroduce the race it exists to close.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> ingestLocks = new ConcurrentHashMap<>();

    private final GatewayMetrics metrics;
    private final UpstreamGit upstreamGit;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;

    public IngestionService(
            GitStorage storage,
            SnapshotRepository snapshotRepository,
            VettingService vettingService,
            ManifestPolicy manifestPolicy,
            ExternalSourceResolver externalSourceResolver,
            ManifestRewriter manifestRewriter,
            SnapshotFactsService factsService,
            GatewayMetrics metrics,
            UpstreamGit upstreamGit,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.vettingService = vettingService;
        this.manifestPolicy = manifestPolicy;
        this.externalSourceResolver = externalSourceResolver;
        this.manifestRewriter = manifestRewriter;
        this.factsService = factsService;
        this.metrics = metrics;
        this.upstreamGit = upstreamGit;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * Fetches the upstream default branch into quarantine, pins the commit, and records a snapshot.
     * Never touches the published repository: upstream changes cannot alter served content until a
     * reviewer approves (publication happens only in ApprovalService).
     *
     * <p>The trigger's identity is recorded on the snapshot (GW_APPROVAL_0010) — a principal for an
     * on-demand ingest or a push, one of the constant sync actors for an automated trigger — so
     * that the approval gate can later tell a reviewer apart from whoever supplied the content. It
     * is a required argument rather than an optional one precisely so that a new ingestion path
     * cannot be added without deciding what identity it acts as.
     *
     * @param actor the identity that triggered this ingestion
     */
    @Requirements({"GW_INGEST_0002", "GW_APPROVAL_0001", "GW_VETTING_0001", "GW_APPROVAL_0010"})
    public Snapshot ingest(Marketplace marketplace, String actor) {
        ReentrantLock lock = ingestLocks.computeIfAbsent(marketplace.id(), id -> new ReentrantLock());
        lock.lock();
        try {
            // Observation only (GW_OBSERVABILITY_0003): timing and outcome around the unchanged ingestion.
            Snapshot snapshot = metrics.observeIngestion(() -> ingestLocked(marketplace, actor));
            marketplaceRepository.recordIngest(marketplace.id(), Marketplace.INGEST_SUCCEEDED, null);
            return snapshot;
        } catch (RuntimeException e) {
            try {
                recordFailure(marketplace, actor, e);
            } catch (RuntimeException recording) {
                // The ingest's own failure is the one the caller must see; this one rides along.
                e.addSuppressed(recording);
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every failed attempt, whatever triggered it, is recorded where the marketplace is read
     * (GW_INGEST_0039), in the ledger (GW_AUDIT_0010) and in the log (GW_INGEST_0038). Only a failure
     * {@link UpstreamGit} raised is described as the upstream's answer; anything else is the
     * gateway's own and says so.
     */
    @Requirements({"GW_INGEST_0038", "GW_INGEST_0039", "GW_AUDIT_0010"})
    private void recordFailure(Marketplace marketplace, String actor, RuntimeException e) {
        UpstreamFailure failure = e instanceof IngestionException ingestion && ingestion.failure() != null
                ? ingestion.failure()
                : UpstreamFailure.unclassified(e);
        String reason = failure.describe();
        log.warn("ingestion of marketplace '{}' failed: {}", marketplace.name(), reason);
        log.debug("ingestion of marketplace '{}' failed", marketplace.name(), e);
        marketplaceRepository.recordIngest(marketplace.id(), Marketplace.INGEST_FAILED, reason);
        auditLogger.record(actor, marketplace, EVENT_INGEST_FAILED, null, reason);
    }

    @Requirements({
        "GW_INGEST_0018",
        "GW_INGEST_0023",
        "GW_INGEST_0024",
        "GW_INGEST_0024.2",
        "GW_INGEST_0027",
        "GW_INGEST_0030",
        "GW_INGEST_0030.3",
        "GW_INGEST_0036"
    })
    private Snapshot ingestLocked(Marketplace marketplace, String actor) {
        try (Repository repo = storage.quarantine(marketplace.name())) {
            ObjectId upstream = fetchIncoming(repo, marketplace);
            // The manifest is decided before anything is pinned, because which commit the snapshot
            // *is* now depends on the answer: a manifest with resolved external sources is served
            // as a composite, not as the upstream commit. The upstream commit stays reachable
            // throughout via refs/quarantine/incoming, and permanently as the composite's parent,
            // so nothing is unreachable at any point in between.
            Served served = serve(repo, upstream);
            ObjectId sha = served.sha();
            // Checked (GW_INGEST_0018): the pin is what a later approval publishes from and what retention
            // treats as the snapshot's anchor. A refused pin that returned quietly would leave a
            // reviewable, approvable row whose objects only the transient staging reference holds.
            RefTransitions.write(repo, "refs/snapshots/" + sha.name(), sha);
            Optional<Snapshot> existing = snapshotRepository.findByMarketplaceAndSha(marketplace.id(), sha.name());
            if (existing.isPresent()) {
                return existing.get();
            }
            String violation = served.violation();
            String state = violation == null ? Snapshot.HELD : Snapshot.REJECTED;
            Snapshot snapshot;
            try {
                // The closure goes in with the row (GW_INGEST_0030.3): a composite and the record of what it
                // resolved are one fact, and the completeness gate at approval is what refuses the
                // state in which they are not.
                snapshot = snapshotRepository.create(
                        marketplace.id(), sha.name(), upstream.name(), state, violation, actor, served.closure());
            } catch (DuplicateKeyException raced) {
                // Belt-and-braces under the per-marketplace lock: another instance of the gateway
                // (or a path the lock cannot see) recorded the same commit first — same content,
                // same answer.
                return snapshotRepository
                        .findByMarketplaceAndSha(marketplace.id(), sha.name())
                        .orElseThrow(() -> raced);
            }
            if (Snapshot.HELD.equals(state)) {
                // Recorded once, before the chain so a failure shows without waiting on it
                // (GW_INGEST_0036). The chain never reads them (GW_VETTING_0039), and a failure here is
                // logged by the service rather than failing the ingestion.
                factsService.record(snapshot, marketplace);
                // The chain runs against the content just pinned, and its outcome gates the
                // approval — it never changes the snapshot's state. A rejected snapshot is already
                // unapprovable, so there is nothing for the chain to protect there.
                vettingService.vet(snapshot, marketplace.name());
            }
            return snapshot;
        } catch (UpstreamException e) {
            throw new IngestionException(
                    "ingestion failed for marketplace '%s': %s".formatted(marketplace.name(), e.getMessage()),
                    e.failure(),
                    e);
        } catch (IOException | GitAPIException e) {
            UpstreamFailure failure = UpstreamFailure.unclassified(e);
            throw new IngestionException(
                    "ingestion failed for marketplace '%s': %s".formatted(marketplace.name(), failure.describe()),
                    failure,
                    e);
        }
    }

    /**
     * Where the incoming commit comes from — the only thing that differs between an upstream
     * marketplace and a gateway-hosted one (GW_INGEST_0017). A hosted marketplace's source is its own
     * origin repository, fetched by filesystem path with the same JGit fetch, so everything from
     * the snapshot pin down is literally the same code and pushed content faces the same manifest
     * validation, the same vetting chain and the same approval gate as fetched content.
     */
    @Requirements({"GW_INGEST_0017"})
    private ObjectId fetchIncoming(Repository repo, Marketplace marketplace) throws GitAPIException, IOException {
        if (!marketplace.hosted()) {
            return upstreamGit.fetchDefaultBranch(repo, marketplace.url(), INCOMING_REF);
        }
        try (Repository origin = storage.hosted(marketplace.name())) {
            if (origin.resolve(Marketplace.LINEAGE_REF) == null) {
                throw new RepositoryNotFoundException(
                        "'%s' has not been published to yet".formatted(marketplace.name()));
            }
            return fetchHostedLineage(repo, origin.getDirectory().getAbsolutePath());
        }
    }

    private static ObjectId fetchHostedLineage(Repository repo, String originPath) throws GitAPIException, IOException {
        try (Git git = new Git(repo)) {
            git.fetch()
                    .setRemote(originPath)
                    .setRefSpecs(new RefSpec("+" + Marketplace.LINEAGE_REF + ":" + INCOMING_REF))
                    .call();
        }
        ObjectId sha = repo.resolve(INCOMING_REF);
        if (sha == null) {
            throw new RepositoryNotFoundException("the origin repository produced no commit");
        }
        return sha;
    }

    /**
     * Which commit this ingestion serves, and why it does not serve one.
     *
     * <p>GW_INGEST_0027 in one type: a failure carries the upstream commit, so the attempt is recorded
     * against reviewable content in the rejected state, and there is no third outcome in which some
     * of a manifest resolved. GW_INGEST_0021 follows from that rather than from a check somewhere —
     * {@code violation != null} is what {@code ingestLocked} maps to rejected, and held is only
     * reachable when the served commit's own manifest is entirely gateway-local.
     */
    private record Served(ObjectId sha, String violation, SnapshotClosure closure) {

        Served(ObjectId sha, String violation) {
            this(sha, violation, null);
        }
    }

    /**
     * Resolves and rewrites when the manifest declares external sources this gateway admits, and is
     * otherwise byte-for-byte the path that shipped before: a local-only manifest is served as the
     * upstream commit, with no composite, no fetch and no new reference.
     */
    @Requirements({"GW_INGEST_0021", "GW_INGEST_0023", "GW_INGEST_0024.1", "GW_INGEST_0027", "GW_INGEST_0030.1"})
    private Served serve(Repository repo, ObjectId upstreamSha) throws IOException {
        byte[] manifestBytes = manifestBytes(repo, upstreamSha);
        if (manifestBytes == null) {
            return new Served(upstreamSha, "missing " + MANIFEST_PATH);
        }
        ManifestPolicy.Evaluation evaluation = manifestPolicy.evaluate(manifestBytes);
        if (evaluation.rejected()) {
            return new Served(upstreamSha, evaluation.violation());
        }
        if (evaluation.admitted().isEmpty()) {
            return new Served(upstreamSha, null);
        }
        ExternalSourceResolver.Resolution resolution = externalSourceResolver.resolve(repo, evaluation.admitted());
        if (resolution.rejected()) {
            return new Served(upstreamSha, resolution.violation());
        }
        List<ManifestRewriter.Graft> grafts = new java.util.ArrayList<>();
        for (ExternalSourceResolver.Resolved resolved : resolution.resolved()) {
            grafts.add(new ManifestRewriter.Graft(
                    resolved.pluginName(), resolved.cloneUrl(), resolved.sha(), resolved.tree()));
        }
        try (RevWalk walk = new RevWalk(repo)) {
            ManifestRewriter.Rewrite rewrite =
                    manifestRewriter.rewrite(repo, walk.parseCommit(upstreamSha), List.copyOf(grafts));
            if (rewrite.violation() != null) {
                return new Served(upstreamSha, rewrite.violation());
            }
            return new Served(rewrite.commit(), null, closure(upstreamSha, resolution.resolved()));
        }
    }

    /** The closure as a value (GW_INGEST_0030.1): what each source was declared as, and what it became. */
    private SnapshotClosure closure(ObjectId upstreamSha, List<ExternalSourceResolver.Resolved> resolved) {
        List<SnapshotClosure.Member> members = new java.util.ArrayList<>();
        for (ExternalSourceResolver.Resolved source : resolved) {
            members.add(new SnapshotClosure.Member(
                    source.pluginName(),
                    source.sourceType(),
                    source.declaredSource(),
                    null,
                    null,
                    source.cloneUrl(),
                    source.sha().name(),
                    source.tree().name(),
                    ManifestRewriter.graftPath(source.pluginName()),
                    source.objectCount(),
                    source.inflatedBytes()));
        }
        return new SnapshotClosure(upstreamSha.name(), manifestRewriter.transformerVersion(), members);
    }

    private static byte[] manifestBytes(Repository repo, ObjectId sha) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(sha);
            try (TreeWalk tree = TreeWalk.forPath(repo, MANIFEST_PATH, commit.getTree())) {
                return tree == null ? null : repo.open(tree.getObjectId(0)).getBytes();
            }
        }
    }
}
