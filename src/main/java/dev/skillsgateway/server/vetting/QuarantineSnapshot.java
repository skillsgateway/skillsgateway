package dev.skillsgateway.server.vetting;

import java.io.IOException;
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
 */
final class QuarantineSnapshot implements SnapshotUnderVetting, AutoCloseable {

    private final long snapshotId;
    private final String marketplace;
    private final String sha;
    private final long maxFileBytes;
    private final Repository repository;
    private final RevCommit commit;

    QuarantineSnapshot(long snapshotId, String marketplace, String sha, long maxFileBytes, Repository repository)
            throws IOException {
        this.snapshotId = snapshotId;
        this.marketplace = marketplace;
        this.sha = sha;
        this.maxFileBytes = maxFileBytes;
        this.repository = repository;
        try (RevWalk walk = new RevWalk(repository)) {
            this.commit = walk.parseCommit(ObjectId.fromString(sha));
        }
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
    public void walk(FileVisitor visitor) throws IOException {
        try (TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                ObjectLoader loader = repository.open(tree.getObjectId(0));
                // Oversized blobs are handed over as null rather than dropped: a connector that
                // cares can report that it could not see the file, which is the fail-closed
                // behaviour. Silently skipping would be a hole an attacker can pad their way into.
                byte[] content = loader.getSize() > maxFileBytes ? null : loader.getBytes();
                visitor.visit(tree.getPathString(), content);
            }
        }
    }

    @Override
    public void close() {
        repository.close();
    }
}
