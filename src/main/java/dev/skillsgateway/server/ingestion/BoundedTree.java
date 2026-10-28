package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * A snapshot's tree, walked within a bound on its entries and depth (GW_INGEST_0072). Git stores a
 * subtree once however often it is referenced, so a handful of tree objects can describe a hundred
 * million paths, and a recursive walk visits each one. This walk enters every subtree occurrence
 * itself and counts every entry, subtrees included, so it stops at the bound whatever the shape.
 */
public final class BoundedTree {

    /** Far above any real marketplace's files and directories, and quick to count to. */
    public static final int MAX_ENTRIES = 200_000;

    /** The default depth bound of resolved external sources (GW_INGEST_0026.7). */
    public static final int MAX_DEPTH = 32;

    /** One file of the tree: its path, object and mode. */
    public record Entry(String path, ObjectId id, FileMode mode) {}

    /** A tree past the bound; the message says which bound and is the snapshot's violation. */
    public static final class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }
    }

    private BoundedTree() {}

    /** Every non-tree entry of {@code tree}, in tree order, or {@link TooLarge} past the bound. */
    @Requirements({"GW_INGEST_0072"})
    public static List<Entry> files(Repository repository, RevTree tree) throws IOException {
        List<Entry> files = new ArrayList<>();
        int visited = 0;
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(tree);
            walk.setRecursive(false);
            while (walk.next()) {
                if (++visited > MAX_ENTRIES) {
                    throw new TooLarge("the tree holds more than %d entries, which the gateway does not walk"
                            .formatted(MAX_ENTRIES));
                }
                if (walk.getDepth() + 1 > MAX_DEPTH) {
                    throw new TooLarge("the tree is more than %d directories deep, which the gateway does not walk"
                            .formatted(MAX_DEPTH));
                }
                if (walk.isSubtree()) {
                    walk.enterSubtree();
                } else {
                    files.add(
                            new Entry(walk.getPathString(), walk.getObjectId(0).copy(), walk.getFileMode(0)));
                }
            }
        }
        return files;
    }
}
