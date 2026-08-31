package dev.skillsgateway.server.storage;

import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

/**
 * Reference transitions that report what they did (GW_0132, GW_0133).
 *
 * <p>{@code RefUpdate} does not throw when an update is refused: {@code LOCK_FAILURE} while another
 * writer holds the lock, {@code IO_FAILURE} underneath it and {@code REJECTED} are all
 * <em>returned</em> values. A caller that discards the result therefore reports success for a
 * reference that never moved, which is how a snapshot came to be recorded as published, entered on
 * the ledger and announced to webhook subscribers while the facade served the previous tip.
 *
 * <p>The allowed-result sets live here once. They were previously written out twice — in
 * {@code StorageMigration} and in {@code FilesystemGitStorage} — and absent at four other call
 * sites, which is the inconsistency this class exists to remove: a fifth caller now has to opt out
 * of the check deliberately rather than forget it.
 */
public final class RefTransitions {

    /** The results a forced write reports when the reference is what we asked for afterwards. */
    private static final Set<RefUpdate.Result> WRITTEN = EnumSet.of(
            RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE);

    /** The results a forced deletion reports when the reference is gone afterwards. */
    private static final Set<RefUpdate.Result> DELETED =
            EnumSet.of(RefUpdate.Result.FORCED, RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE);

    private RefTransitions() {}

    /** Points {@code ref} at {@code id}, raising when the update did not take effect. */
    @Requirements({"GW_0133"})
    public static void write(Repository repository, String ref, ObjectId id) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.forceUpdate();
        if (!WRITTEN.contains(result)) {
            throw new IOException("could not write %s in %s: %s".formatted(ref, describe(repository), result));
        }
    }

    /**
     * Removes {@code ref} if it is there, raising when the deletion did not take effect. Force is
     * required because the reference is not being fast-forwarded to anything — the whole point is
     * that nothing replaces it.
     */
    @Requirements({"GW_0133"})
    public static void delete(Repository repository, String ref) throws IOException {
        if (repository.exactRef(ref) == null) {
            return;
        }
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.delete();
        if (!DELETED.contains(result)) {
            throw new IOException("could not delete %s in %s: %s".formatted(ref, describe(repository), result));
        }
    }

    /**
     * Points the symbolic reference {@code ref} at {@code target}, raising when the link did not
     * take effect. Detached, so the update names the symbolic reference itself rather than whatever
     * it currently resolves to.
     */
    @Requirements({"GW_0133"})
    public static void link(Repository repository, String ref, String target) throws IOException {
        RefUpdate.Result result = repository.updateRef(ref, true).link(target);
        if (!WRITTEN.contains(result)) {
            throw new IOException(
                    "could not link %s to %s in %s: %s".formatted(ref, target, describe(repository), result));
        }
    }

    /** A DFS repository has no directory, so its identity comes from the description instead. */
    private static String describe(Repository repository) {
        return repository.getDirectory() != null ? repository.getDirectory().toString() : repository.getIdentifier();
    }
}
