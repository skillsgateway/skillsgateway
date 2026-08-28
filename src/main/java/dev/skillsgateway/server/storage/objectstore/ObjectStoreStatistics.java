package dev.skillsgateway.server.storage.objectstore;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the things this backend can only be trusted about if they are counted.
 *
 * <p>Retry on a lost compare-and-swap is ours to write, ours to bound and — the part that is easy
 * to forget — ours to count. An unbounded retry is a hang; a bounded retry nobody counts is a
 * design whose contention is invisible until it is an outage. These are deliberately plain
 * counters rather than meters: wiring them to the metrics surface is separate work, and a counter
 * that exists is what makes that wiring a rename instead of an investigation.
 */
public final class ObjectStoreStatistics {

    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong exhaustions = new AtomicLong();
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicLong packDownloads = new AtomicLong();
    private final AtomicLong packCacheHits = new AtomicLong();

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
}
