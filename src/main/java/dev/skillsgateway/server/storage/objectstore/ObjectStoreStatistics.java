package dev.skillsgateway.server.storage.objectstore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the things this backend can only be trusted about if they are counted.
 *
 * <p>Retry on a lost compare-and-swap is ours to write, ours to bound and — the part that is easy
 * to forget — ours to count. An unbounded retry is a hang; a bounded retry nobody counts is a
 * design whose contention is invisible until it is an outage.
 *
 * <p>These stay plain counters rather than meters so that the backend has no dependency on a
 * metrics registry and works identically in a test that has none. Publishing them is somebody
 * else's job — {@code ObjectStoreMetrics} binds them — which also keeps the meter names in the one
 * place meter names live rather than scattered through the storage code.
 *
 * <p>Two of them are levels rather than counts, and are kept per repository so that a replica
 * serving several marketplaces reports their sum rather than whichever it touched last. Neither is
 * tagged by repository anywhere: the marketplace is not a metric dimension, because a time series
 * per marketplace is unbounded cardinality on a value the adoption API already answers exactly.
 */
public final class ObjectStoreStatistics {

    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong exhaustions = new AtomicLong();
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicLong packDownloads = new AtomicLong();
    private final AtomicLong packCacheHits = new AtomicLong();
    private final Map<String, Long> walDepths = new ConcurrentHashMap<>();
    private final Map<String, Long> livePacks = new ConcurrentHashMap<>();

    void conflict() {
        conflicts.incrementAndGet();
        retries.incrementAndGet();
    }

    void exhausted() {
        exhaustions.incrementAndGet();
    }

    void refreshed() {
        refreshes.incrementAndGet();
    }

    void packDownloaded() {
        packDownloads.incrementAndGet();
    }

    void packCacheHit() {
        packCacheHits.incrementAndGet();
    }

    /** One more write-ahead entry written for {@code repository} and not yet known to be folded. */
    void walAppended(String repository) {
        walDepths.merge(repository, 1L, Long::sum);
    }

    /**
     * The write-ahead depth a maintenance pass actually counted, which replaces the running
     * estimate. Between passes the estimate sees only this replica's own appends; a pass reads the
     * bucket, so what it saw is the truth for every writer.
     */
    void walDepthObserved(String repository, long depth) {
        walDepths.put(repository, depth);
    }

    /** How many packs the manifest just read for {@code repository} says are live. */
    void livePacksObserved(String repository, long packs) {
        livePacks.put(repository, packs);
    }

    /** Conditional writes the store refused because the manifest had moved on. */
    public long conflicts() {
        return conflicts.get();
    }

    /** Transition attempts made after such a refusal. */
    public long retries() {
        return retries.get();
    }

    /** Transitions abandoned because the bounded retry ran out. */
    public long exhaustions() {
        return exhaustions.get();
    }

    /** Freshness checks that found the manifest had changed under a cached reference map. */
    public long refreshes() {
        return refreshes.get();
    }

    /** Packs fetched from the store into the local cache. */
    public long packDownloads() {
        return packDownloads.get();
    }

    /** Pack opens served from the local cache without touching the store. */
    public long packCacheHits() {
        return packCacheHits.get();
    }

    /** Outstanding write-ahead entries across every repository this replica has written to. */
    public long walDepth() {
        return walDepths.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Live packs across every repository whose manifest this replica has read. */
    public long livePacks() {
        return livePacks.values().stream().mapToLong(Long::longValue).sum();
    }
}
