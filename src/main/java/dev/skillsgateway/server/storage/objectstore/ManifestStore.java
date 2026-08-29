package dev.skillsgateway.server.storage.objectstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One repository's manifest, read, cached, and swapped by compare-and-swap.
 *
 * <p>This is the only serialization point in the backend. Every transition — a publication, a
 * revocation, a whole {@code receive-pack} of many references, a pack commit, a compaction — is a
 * read of this object, a computation against what was read, and one conditional write of the
 * result. A writer that loses the race re-reads and re-evaluates; it has written nothing that
 * needs cleaning up, because the packs it uploaded first are immutable, content-named and
 * unreferenced until this object names them.
 *
 * <p>Two properties are worth stating because they are the ones a reviewer should check.
 *
 * <p><b>Retry is bounded and counted.</b> An unbounded retry under contention is a hang, and an
 * uncounted one hides the contention until it is an outage. Running out of attempts is an
 * {@link IOException} naming the transition, never a quiet "nothing happened" — on the revocation
 * path a silent failure would have the ledger say a marketplace stopped serving while the revoked
 * commit is still on the wire.
 *
 * <p><b>Freshness is bounded too, and that bound is a trust-boundary property.</b> JGit's DFS
 * reference database caches its reference map until something clears it. With one process that was
 * invisible; with several replicas over one bucket it means an unpublished snapshot stays
 * advertised by every replica whose cache is still warm. {@link #freshen()} is a conditional
 * {@code GET} — {@code O(1)}, no body when nothing changed — and the default freshness of zero
 * makes the bound "the next reference advertisement".
 */
public final class ManifestStore {

    /** How many times a losing writer re-reads and retries before giving up. */
    static final int MAX_ATTEMPTS = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A manifest together with the version token a conditional write must present to replace it. */
    public record Snapshot(RepositoryManifest manifest, String etag) {}

    /**
     * A computed transition: the manifest to write, or null to say the state already satisfies the
     * caller and nothing should be written.
     */
    public record Step<T>(RepositoryManifest next, T result) {

        /** Nothing to write; this is the answer. */
        public static <T> Step<T> unchanged(T result) {
            return new Step<>(null, result);
        }
    }

    /** A transition, re-evaluated from scratch against fresh state on every attempt. */
    @FunctionalInterface
    public interface Transition<T> {
        Step<T> apply(RepositoryManifest current) throws IOException;
    }

    private final ObjectStoreClient store;
    private final String manifestKey;
    private final String walPrefix;
    private final Duration freshness;
    private final ObjectStoreStatistics statistics;

    private final AtomicLong lastCheckedNanos = new AtomicLong(Long.MIN_VALUE);
    private volatile Snapshot cached;

    ManifestStore(
            ObjectStoreClient store,
            String manifestKey,
            String walPrefix,
            Duration freshness,
            ObjectStoreStatistics statistics) {
        this.store = store;
        this.manifestKey = manifestKey;
        this.walPrefix = walPrefix;
        this.freshness = freshness;
        this.statistics = statistics;
    }

    /** Whether this repository exists at all — that is, whether its manifest is in the bucket. */
    public boolean exists() throws IOException {
        return cached != null || store.exists(manifestKey);
    }

    /**
     * Create the repository if it is not there, exactly once. Two replicas creating the same
     * marketplace at the same instant is not a race that needs a lock: one wins the
     * create-exactly-once write and the other reads what the winner wrote.
     */
    public synchronized Snapshot createIfAbsent(RepositoryManifest initial) throws IOException {
        Optional<Snapshot> existing = load();
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<String> etag = store.putIfAbsent(manifestKey, initial.toJson());
        if (etag.isPresent()) {
            Snapshot created = new Snapshot(initial, etag.get());
            remember(created);
            return created;
        }
        return reload();
    }

    /** The current manifest, read once and then cached until something invalidates it. */
    public Snapshot current() throws IOException {
        Snapshot snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        return reload();
    }

    /** Read the manifest again, unconditionally. */
    public synchronized Snapshot reload() throws IOException {
        return load().orElseThrow(() -> new IOException("no repository manifest at " + manifestKey));
    }

    /**
     * Re-check the manifest if the cached copy is older than the freshness bound, and say whether
     * it moved. A conditional {@code GET}, so the common answer costs a round trip and no body.
     */
    public boolean freshen() throws IOException {
        Snapshot snapshot = cached;
        if (snapshot == null) {
            return false;
        }
        long now = System.nanoTime();
        if (!freshness.isZero() && now - lastCheckedNanos.get() < freshness.toNanos()) {
            return false;
        }
        lastCheckedNanos.set(now);
        Optional<ObjectStoreClient.StoredObject> changed = store.getIfChanged(manifestKey, snapshot.etag());
        if (changed.isEmpty()) {
            return false;
        }
        remember(new Snapshot(
                RepositoryManifest.parse(changed.get().body()), changed.get().etag()));
        statistics.refreshed();
        return true;
    }

    /**
     * Evaluate {@code body} against the current manifest and commit its result as one conditional
     * write, re-evaluating from fresh state each time the store refuses.
     *
     * @param description what is being applied, used in the failure when the bound runs out
     */
    public <T> T transact(String description, Transition<T> body) throws IOException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Snapshot base = attempt == 0 ? current() : reload();
            Step<T> step = body.apply(base.manifest());
            if (step.next() == null) {
                return step.result();
            }
            appendWal(description, step.next());
            Optional<String> etag = store.putIfMatch(manifestKey, step.next().toJson(), base.etag());
            if (etag.isPresent()) {
                remember(new Snapshot(step.next(), etag.get()));
                return step.result();
            }
            statistics.conflict();
        }
        statistics.exhausted();
        throw new IOException(("could not apply %s: the repository manifest %s was rewritten by another writer"
                        + " on each of %d attempts")
                .formatted(description, manifestKey, MAX_ATTEMPTS));
    }

    /**
     * The write-ahead entry for a transition, written before the manifest that publishes it, so
     * the bucket records the intent as well as the outcome. An entry a losing writer left behind
     * names a sequence the manifest never reached and is collected by compaction.
     */
    private void appendWal(String description, RepositoryManifest next) throws IOException {
        String key = "%s%016d-%s".formatted(walPrefix, next.sequence(), UUID.randomUUID());
        Map<String, Object> entry = Map.of(
                "sequence",
                next.sequence(),
                "transition",
                description,
                "refs",
                next.refs(),
                "packs",
                next.packs().stream().map(RepositoryManifest.PackEntry::name).toList());
        store.put(key, MAPPER.writeValueAsBytes(entry));
    }

    /** Write-ahead entries the manifest has already absorbed, oldest first. */
    List<String> foldedWalEntries(long throughSequence) throws IOException {
        return store.list(walPrefix).stream()
                .filter(key -> sequenceOf(key) <= throughSequence)
                .sorted()
                .toList();
    }

    private long sequenceOf(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        int dash = name.indexOf('-');
        try {
            return Long.parseLong(dash < 0 ? name : name.substring(0, dash));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private Optional<Snapshot> load() throws IOException {
        Optional<ObjectStoreClient.StoredObject> object = store.get(manifestKey);
        if (object.isEmpty()) {
            return Optional.empty();
        }
        Snapshot snapshot = new Snapshot(
                RepositoryManifest.parse(object.get().body()), object.get().etag());
        remember(snapshot);
        return Optional.of(snapshot);
    }

    private void remember(Snapshot snapshot) {
        cached = snapshot;
        lastCheckedNanos.set(System.nanoTime());
    }
}
