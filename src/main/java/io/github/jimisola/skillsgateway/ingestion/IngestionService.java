package io.github.jimisola.skillsgateway.ingestion;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.vetting.VettingService;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final String INCOMING_REF = "refs/quarantine/incoming";
    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final VettingService vettingService;

    /**
     * One lock per marketplace: with sync modes (GW_0057, GW_0058) a manual ingest, a scheduler
     * tick and a webhook trigger can arrive together, and a concurrent same-marketplace ingest
     * races both JGit's ref lockfile on the incoming ref and the exists-check-then-insert against
     * the (marketplace_id, sha) unique constraint. Serializing at this one choke point covers
     * every trigger path. Entries are never removed; the estate of marketplaces is small and an
     * eviction scheme would reintroduce the race it exists to close.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> ingestLocks = new ConcurrentHashMap<>();

    public IngestionService(GitStorage storage, SnapshotRepository snapshotRepository, VettingService vettingService) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.vettingService = vettingService;
    }

    /**
     * Fetches the upstream default branch into quarantine, pins the commit, and records a snapshot.
     * Never touches the published repository: upstream changes cannot alter served content until a
     * reviewer approves (publication happens only in ApprovalService).
     */
    @Requirements({"GW_0002", "GW_0004", "GW_0037"})
    public Snapshot ingest(Marketplace marketplace) {
        ReentrantLock lock = ingestLocks.computeIfAbsent(marketplace.id(), id -> new ReentrantLock());
        lock.lock();
        try {
            return ingestLocked(marketplace);
        } finally {
            lock.unlock();
        }
    }

    private Snapshot ingestLocked(Marketplace marketplace) {
        try (Repository repo = storage.quarantine(marketplace.name())) {
            ObjectId sha = fetchUpstreamHead(repo, marketplace.url());
            RefUpdate pin = repo.updateRef("refs/snapshots/" + sha.name());
            pin.setNewObjectId(sha);
            pin.forceUpdate();
            Optional<Snapshot> existing = snapshotRepository.findByMarketplaceAndSha(marketplace.id(), sha.name());
            if (existing.isPresent()) {
                return existing.get();
            }
            String violation = validateManifest(repo, sha);
            String state = violation == null ? Snapshot.HELD : Snapshot.REJECTED;
            Snapshot snapshot;
            try {
                snapshot = snapshotRepository.create(marketplace.id(), sha.name(), state, violation);
            } catch (DuplicateKeyException raced) {
                // Belt-and-braces under the per-marketplace lock: another instance of the gateway
                // (or a path the lock cannot see) recorded the same commit first — same content,
                // same answer.
                return snapshotRepository
                        .findByMarketplaceAndSha(marketplace.id(), sha.name())
                        .orElseThrow(() -> raced);
            }
            if (Snapshot.HELD.equals(state)) {
                // The chain runs against the content just pinned, and its outcome gates the
                // approval — it never changes the snapshot's state. A rejected snapshot is already
                // unapprovable, so there is nothing for the chain to protect there.
                vettingService.vet(snapshot, marketplace.name());
            }
            return snapshot;
        } catch (IOException | GitAPIException e) {
            throw new IngestionException("ingestion failed for marketplace '%s'".formatted(marketplace.name()), e);
        }
    }

    private static ObjectId fetchUpstreamHead(Repository repo, String url) throws GitAPIException, IOException {
        try (Git git = new Git(repo)) {
            try {
                git.fetch()
                        .setRemote(url)
                        .setRefSpecs(new RefSpec("+HEAD:" + INCOMING_REF))
                        .call();
            } catch (GitAPIException e) {
                // Some transports reject a HEAD refspec; resolve the default branch and retry.
                String branch = resolveDefaultBranch(url);
                git.fetch()
                        .setRemote(url)
                        .setRefSpecs(new RefSpec("+" + branch + ":" + INCOMING_REF))
                        .call();
            }
        }
        ObjectId sha = repo.resolve(INCOMING_REF);
        if (sha == null) {
            throw new RepositoryNotFoundException("fetch from %s produced no commit".formatted(url));
        }
        return sha;
    }

    private static String resolveDefaultBranch(String url) throws GitAPIException {
        Map<String, Ref> refs = Git.lsRemoteRepository().setRemote(url).callAsMap();
        Ref head = refs.get(Constants.HEAD);
        if (head == null || head.getObjectId() == null) {
            throw new IngestionException("upstream %s has no HEAD".formatted(url));
        }
        if (head.isSymbolic()) {
            return head.getTarget().getName();
        }
        ObjectId id = head.getObjectId();
        for (String preferred : List.of("refs/heads/main", "refs/heads/master")) {
            Ref candidate = refs.get(preferred);
            if (candidate != null && id.equals(candidate.getObjectId())) {
                return preferred;
            }
        }
        return refs.entrySet().stream()
                .filter(e -> e.getKey().startsWith(Constants.R_HEADS)
                        && id.equals(e.getValue().getObjectId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IngestionException("cannot determine default branch of %s".formatted(url)));
    }

    private static String validateManifest(Repository repo, ObjectId sha) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(sha);
            try (TreeWalk tree = TreeWalk.forPath(repo, MANIFEST_PATH, commit.getTree())) {
                if (tree == null) {
                    return "missing " + MANIFEST_PATH;
                }
                byte[] bytes = repo.open(tree.getObjectId(0)).getBytes();
                return ManifestPolicy.validate(bytes);
            }
        }
    }
}
