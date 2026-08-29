package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsRefDatabase;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.util.RefList;

/**
 * References as entries in the manifest, changed by compare-and-swap.
 *
 * <p>JGit's {@code DfsRefDatabase} asks for exactly three things — read every reference, put one if
 * it still matches, remove one if it still matches — which is the manifest transition written in
 * JGit's own vocabulary. What that vocabulary does not say, and what this class has to absorb, is
 * that <b>the precondition JGit hands down is per-reference while the one the store enforces is
 * per-repository</b>.
 *
 * <p>An approval writing {@code refs/snapshots/A} and {@code refs/heads/main} and a retention pass
 * deleting {@code refs/snapshots/B} both hold valid reference-level preconditions, and both are
 * correct; one of them still loses the manifest's version race. Reporting that as {@code false}
 * would be wrong, because {@code DfsRefUpdate.doUpdate} turns {@code false} into
 * {@code LOCK_FAILURE}, JGit does not retry, and the caller is told an update that should have
 * succeeded failed. So a refused conditional write is re-read, the <em>reference-level</em>
 * precondition is checked again against the fresh state, and the write is retried — bounded, and
 * counted. {@code false} means one thing only: the reference itself moved.
 *
 * <p>The other property this class owns is freshness. {@code DfsRefDatabase} caches its reference
 * map until something clears it, which with one process was invisible and with several replicas
 * over one bucket means <b>a revoked snapshot stays advertised by every replica whose cache is
 * still warm</b>. Every read path here first re-checks the manifest with a conditional {@code GET},
 * so the revocation bound is the configured freshness — zero by default, which makes the bound
 * "the next reference advertisement". That is a trust-boundary property, not a tuning knob.
 */
final class ObjectStoreRefDatabase extends DfsRefDatabase {

    private final ObjectStoreRepository repository;
    private final ManifestStore manifests;

    ObjectStoreRefDatabase(ObjectStoreRepository repository, ManifestStore manifests) {
        super(repository);
        this.repository = repository;
        this.manifests = manifests;
    }

    // --- reads: bounded staleness before every one of them ---------------------------------

    @Override
    public Ref exactRef(String name) throws IOException {
        repository.freshen();
        return super.exactRef(name);
    }

    @Override
    public Map<String, Ref> getRefs(String prefix) throws IOException {
        repository.freshen();
        return super.getRefs(prefix);
    }

    @Override
    public boolean isNameConflicting(String refName) throws IOException {
        repository.freshen();
        return super.isNameConflicting(refName);
    }

    @Override
    protected RefCache scanAllRefs() throws IOException {
        RepositoryManifest manifest = manifests.current().manifest();
        RefList.Builder<Ref> ids = new RefList.Builder<>(manifest.refs().size());
        RefList.Builder<Ref> symbolic = new RefList.Builder<>();
        manifest.refs().forEach((name, value) -> {
            Ref ref = toRef(name, value);
            ids.add(ref);
            if (ref.isSymbolic()) {
                symbolic.add(ref);
            }
        });
        ids.sort();
        symbolic.sort();
        return new RefCache(ids.toRefList(), symbolic.toRefList());
    }

    // --- writes: one conditional write each, with the mismatch absorbed here -----------------

    @Override
    protected boolean compareAndPut(Ref oldRef, Ref newRef) throws IOException {
        String name = newRef.getName();
        String value = encode(newRef);
        return manifests.transact("update of " + name, current -> {
            if (!matches(oldRef, current.ref(name))) {
                return ManifestStore.Step.unchanged(false);
            }
            if (value.equals(current.ref(name))) {
                return ManifestStore.Step.unchanged(true);
            }
            return new ManifestStore.Step<>(current.withRef(name, value), true);
        });
    }

    @Override
    protected boolean compareAndRemove(Ref oldRef) throws IOException {
        String name = oldRef.getName();
        return manifests.transact("removal of " + name, current -> {
            if (!matches(oldRef, current.ref(name))) {
                return ManifestStore.Step.unchanged(false);
            }
            if (current.ref(name) == null) {
                return ManifestStore.Step.unchanged(true);
            }
            return new ManifestStore.Step<>(current.withoutRef(name), true);
        });
    }

    // --- the advertised capability, and what has to be true for it to be honest ---------------

    /**
     * True, and it has to be, because the base class defaults it to false and {@code ReceivePack}
     * derives the advertised {@code atomic} capability from this method alone. A backend that
     * inherited the default would silently stop offering atomic pushes to publishers — an
     * observable, client-visible difference between two backends that the storage contract suite
     * exists to forbid. Saying true is only honest because {@link #newBatchUpdate()} really does
     * commit the whole command list as one conditional write.
     */
    @Override
    public boolean performsAtomicTransactions() {
        return true;
    }

    @Override
    public BatchRefUpdate newBatchUpdate() {
        return new ObjectStoreBatchRefUpdate(this, manifests);
    }

    /**
     * No reflog. The manifest records what a repository is, not how it got there — the gateway's
     * own append-only ledger is the record of who changed what, and it is the one an auditor is
     * entitled to trust.
     */
    @Override
    public org.eclipse.jgit.lib.ReflogReader getReflogReader(Ref ref) {
        return null;
    }

    ManifestStore manifests() {
        return manifests;
    }

    /** Drop the cached reference map; the manifest under it has already moved. */
    void invalidate() {
        refresh();
    }

    // --- encoding -----------------------------------------------------------------------------

    /**
     * Whether the reference is still what the caller read. JGit expresses "expected not to exist"
     * as a {@code NEW}-storage reference with a null object id, which is why absence is a value
     * here rather than a special case.
     */
    static boolean matches(Ref expected, String stored) {
        if (expected == null || (expected.getStorage() == Ref.Storage.NEW && expected.getObjectId() == null)) {
            return stored == null;
        }
        if (expected.isSymbolic()) {
            return (RepositoryManifest.SYMBOLIC_PREFIX + expected.getTarget().getName()).equals(stored);
        }
        return expected.getObjectId() != null && expected.getObjectId().name().equals(stored);
    }

    static String encode(Ref ref) {
        return ref.isSymbolic()
                ? RepositoryManifest.SYMBOLIC_PREFIX + ref.getTarget().getName()
                : ref.getObjectId().name();
    }

    static Ref toRef(String name, String value) {
        if (value.startsWith(RepositoryManifest.SYMBOLIC_PREFIX)) {
            String target = value.substring(RepositoryManifest.SYMBOLIC_PREFIX.length());
            return new SymbolicRef(name, new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null));
        }
        return new ObjectIdRef.Unpeeled(Ref.Storage.PACKED, name, ObjectId.fromString(value));
    }
}
