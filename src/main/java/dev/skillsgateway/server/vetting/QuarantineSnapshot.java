package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * {@link SnapshotUnderVetting} backed by the marketplace's quarantine repository, pinned to the
 * snapshot's commit.
 *
 * <p>The repository handle is held by the run, not by the connectors: one open per chain run, each
 * connector walking the same commit tree. Connectors only ever see paths and bytes, so no connector
 * can reach a ref, another commit, or another marketplace.
 *
 * <p>The tree itself is walked once, in the constructor, into an index of paths and blob ids
 * (GW_0162). Every connector's walk then iterates that index, opens only the blobs its selection
 * asks for, and reuses what an earlier connector already read — so a chain of three connectors
 * costs one tree walk and one inflation of each blob any of them wanted, rather than three of each.
 *
 * <p>Not thread-safe by construction but safe under the concurrency the chain actually has: a
 * connector abandoned after its timeout may still be walking while the next one starts, so the
 * index is immutable and the cache and its budget are concurrent.
 */
final class QuarantineSnapshot implements SnapshotUnderVetting, AutoCloseable {

    /** One file of the pinned tree: where it is, and which blob it is. No content. */
    private record Entry(String path, ObjectId blob) {}

    private final long snapshotId;
    private final String marketplace;
    private final String sha;
    private final long maxFileBytes;
    private final Repository repository;
    private final List<Entry> entries;
    private final Map<ObjectId, byte[]> cache = new ConcurrentHashMap<>();
    private final AtomicLong cacheBudget;

    QuarantineSnapshot(
            long snapshotId,
            String marketplace,
            String sha,
            long maxFileBytes,
            long contentCacheBytes,
            Repository repository)
            throws IOException {
        this.snapshotId = snapshotId;
        this.marketplace = marketplace;
        this.sha = sha;
        this.maxFileBytes = maxFileBytes;
        this.repository = repository;
        this.cacheBudget = new AtomicLong(contentCacheBytes);
        RevCommit commit;
        try (RevWalk walk = new RevWalk(repository)) {
            commit = walk.parseCommit(ObjectId.fromString(sha));
        }
        this.entries = index(repository, commit);
    }

    @Override
    public long snapshotId() {
        return snapshotId;
    }

    @Override
    public String marketplace() {
        return marketplace;
    }

    @Override
    public String sha() {
        return sha;
    }

    @Override
    @Requirements({"GW_0162"})
    public void walk(Predicate<String> wanted, FileVisitor visitor) throws IOException {
        for (Entry entry : entries) {
            if (wanted.test(entry.path())) {
                visitor.visit(entry.path(), content(entry.blob()));
            }
        }
    }

    @Override
    public void close() {
        repository.close();
    }

    /** The pinned tree's files, in tree order. Reads tree objects only — no blob is opened. */
    private static List<Entry> index(Repository repository, RevCommit commit) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                entries.add(new Entry(tree.getPathString(), tree.getObjectId(0).copy()));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * A blob's bytes, from the run's cache when an earlier connector already read it. Keyed by blob
     * id rather than path, so a file copied into every plugin costs one entry.
     */
    private byte[] content(ObjectId blob) throws IOException {
        byte[] cached = cache.get(blob);
        if (cached != null) {
            return cached;
        }
        ObjectLoader loader = repository.open(blob);
        // Oversized blobs are handed over as null rather than dropped: a connector that cares can
        // report that it could not see the file, which is the fail-closed behaviour. Silently
        // skipping would be a hole an attacker can pad their way into.
        if (loader.getSize() > maxFileBytes) {
            return null;
        }
        byte[] content = loader.getBytes();
        // Past the run's budget the bytes are still returned, just not kept: a snapshot larger than
        // the budget costs re-reads, never coverage. Quarantined content is upstream's to choose,
        // so an unbounded cache here would be a memory-exhaustion primitive.
        if (cacheBudget.get() >= content.length) {
            cacheBudget.addAndGet(-content.length);
            cache.put(blob, content);
        }
        return content;
    }
}
