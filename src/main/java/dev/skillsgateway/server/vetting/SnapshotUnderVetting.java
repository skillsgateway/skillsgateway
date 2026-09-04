package dev.skillsgateway.server.vetting;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * What a connector is given: the snapshot's identity and read-only access to the bytes pinned to
 * its commit SHA.
 *
 * <p>Deliberately not a JGit {@code Repository}. A connector must not be able to write to
 * quarantine, move a ref, or reach another marketplace's content, so the seam it gets is a walk
 * over one commit tree and nothing more. That also means an out-of-process connector can be handed
 * the same abstraction over a fetched tarball without changing the SPI.
 */
public interface SnapshotUnderVetting {

    /** Snapshot row id — the key verdicts are recorded against. */
    long snapshotId();

    /** Gateway-local marketplace name. */
    String marketplace();

    /** Upstream commit SHA the snapshot is pinned to. */
    String sha();

    /**
     * Visits the files of the snapshot tree whose paths {@code wanted} accepts, in tree order.
     * Files larger than the configured cap are reported to the visitor as skipped rather than
     * silently dropped.
     *
     * <p>A connector that reads only some file kinds says so here rather than filtering inside its
     * visitor: the selection is applied before content is read, so a blob no connector asked for is
     * never inflated (GW_0162).
     */
    void walk(Predicate<String> wanted, FileVisitor visitor) throws IOException;

    /** {@link #walk(Predicate, FileVisitor)} over the whole tree. */
    default void walk(FileVisitor visitor) throws IOException {
        walk(path -> true, visitor);
    }

    /** Receiver for {@link #walk(Predicate, FileVisitor)}. */
    @FunctionalInterface
    interface FileVisitor {

        /**
         * @param path repository-relative path
         * @param content the file's bytes, or {@code null} when the file exceeded the size cap
         */
        void visit(String path, byte[] content);
    }
}
