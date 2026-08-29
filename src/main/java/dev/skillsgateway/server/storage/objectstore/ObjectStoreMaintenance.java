package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.NullProgressMonitor;

/**
 * The maintenance the write-ahead design brings with it: fold the log, repack, and delete what
 * nothing can reach any more — none of it urgent, all of it eventually necessary.
 *
 * <p>Two things make this safe to run from any replica at any time, with no coordination.
 *
 * <p>It is itself a compare-and-swap. Repacking commits its result through the same conditional
 * write every other transition uses, so a compactor that loses a race loses harmlessly: the packs
 * it wrote are unreferenced objects, which the next pass collects.
 *
 * <p>And pack deletion waits. A pack that has just stopped being referenced may still be feeding
 * an {@code upload-pack} on another replica, which would see a 404 in the middle of a fetch — an
 * error the client cannot distinguish from corruption. So a pack is tombstoned when it stops being
 * referenced and deleted only once it has been unreferenced for longer than the longest fetch
 * anyone plausibly serves.
 */
public final class ObjectStoreMaintenance {

    private final Duration packGrace;

    public ObjectStoreMaintenance(Duration packGrace) {
        this.packGrace = packGrace;
    }

    /** Repack, fold the write-ahead log, and delete packs whose grace period has run out. */
    public void compact(ObjectStoreRepository repository) throws IOException {
        new DfsGarbageCollector(repository).pack(NullProgressMonitor.INSTANCE);
        repository.getRefDatabase().refresh();
        foldWriteAheadLog(repository);
        deleteExpiredPacks(repository);
    }

    /**
     * Delete the write-ahead entries the manifest has already absorbed. The manifest is the folded
     * state; an entry at or below its sequence describes a transition the manifest already records,
     * and an entry a losing writer left behind names a sequence the manifest reached by another
     * route. Neither is needed to answer what the repository is.
     */
    public void foldWriteAheadLog(ObjectStoreRepository repository) throws IOException {
        ManifestStore manifests = repository.manifests();
        long throughSequence = manifests.current().manifest().sequence();
        ObjectStoreClient store = repository.getObjectDatabase().client();
        for (String key : manifests.foldedWalEntries(throughSequence)) {
            store.delete(key);
        }
        // Counted from the bucket rather than from what this replica happens to have written, so
        // the depth gauge is exact at least once per pass however many replicas are writing.
        manifests.observeWalDepth(store.list(manifests.walPrefix()).size());
    }

    /**
     * Delete the objects of packs that stopped being referenced longer ago than the grace period,
     * and forget their tombstones.
     *
     * @return the pack names deleted
     */
    public List<String> deleteExpiredPacks(ObjectStoreRepository repository) throws IOException {
        ManifestStore manifests = repository.manifests();
        long cutoff = System.currentTimeMillis() - packGrace.toMillis();
        Map<String, Long> tombstones = manifests.current().manifest().tombstones();
        List<String> expired = tombstones.entrySet().stream()
                .filter(entry -> entry.getValue() <= cutoff)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (expired.isEmpty()) {
            return List.of();
        }
        // The manifest stops naming them first. A crash after this point leaves objects nothing
        // references, which the next pass finds by listing; a crash the other way round would
        // leave the manifest naming objects that are gone.
        manifests.transact(
                "forgetting %d expired pack(s)".formatted(expired.size()),
                current -> new ManifestStore.Step<>(current.withoutTombstones(expired), null));
        ObjectStoreClient store = repository.getObjectDatabase().client();
        String prefix = repository.getObjectDatabase().objectPrefix();
        for (String pack : expired) {
            for (PackExt ext : PackExt.values()) {
                store.delete(prefix + pack + "." + ext.getExtension());
            }
        }
        return expired;
    }
}
