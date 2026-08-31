package dev.skillsgateway.server.storage;

import java.io.IOException;
import java.io.InputStream;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;

/**
 * Copies the objects one commit needs from one repository into another, using only public JGit API
 * and without either repository having a filesystem path.
 *
 * <p>Publication used to transfer objects by fetching from
 * {@code quarantine.getDirectory().getAbsolutePath()}. A {@code DfsRepository} has no working
 * directory — {@code getDirectory()} returns {@code null} — so on the object-store backend that
 * expression raises {@code NullPointerException} before publication reaches the reference update at
 * all. Nothing detected it because no test drove approval on that backend. Transfer therefore
 * cannot be path-based, and this is the one mechanism both backends share.
 *
 * <p>Objects the destination already holds are skipped, so the first publication of a marketplace
 * pays for its history and every later one pays only for what changed.
 */
final class GitObjectTransfer {

    private GitObjectTransfer() {}

    /** Copies everything reachable from {@code tip} that {@code destination} does not already have. */
    static void copy(Repository source, Repository destination, ObjectId tip) throws IOException {
        try (ObjectReader reader = source.newObjectReader();
                ObjectInserter inserter = destination.newObjectInserter();
                ObjectWalk walk = new ObjectWalk(reader)) {
            walk.markStart(walk.parseAny(tip));
            RevObject commit;
            while ((commit = walk.next()) != null) {
                copyOne(reader, inserter, destination, commit);
            }
            RevObject object;
            while ((object = walk.nextObject()) != null) {
                copyOne(reader, inserter, destination, object);
            }
            inserter.flush();
        }
    }

    private static void copyOne(ObjectReader reader, ObjectInserter inserter, Repository destination, AnyObjectId id)
            throws IOException {
        if (destination.getObjectDatabase().has(id)) {
            return;
        }
        ObjectLoader loader = reader.open(id);
        try (InputStream content = loader.openStream()) {
            inserter.insert(loader.getType(), loader.getSize(), content);
        }
    }
}
