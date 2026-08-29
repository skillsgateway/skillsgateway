package dev.skillsgateway.server.observability;

import dev.skillsgateway.server.storage.objectstore.ObjectStoreStatistics;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * The object-storage backend's own counters, published as meters (GW_0116).
 *
 * <p>The backend keeps plain counters so that it needs no registry to work. This is where they
 * become telemetry, and it is deliberately the only place: the meter names live beside
 * {@link GatewayMetrics}'s rather than scattered through the storage code, and they are
 * <em>recorded</em> unconditionally through the auto-configured registry exactly as everything
 * else here is, so a deployment that turns on export gets them without a gateway change.
 *
 * <p>What they are for is stated plainly, because a metric nobody can act on is noise. Conflicts
 * and retries are the contention the whole compare-and-swap design trades on being low; design
 * decision 3 says a distributed lock becomes worth revisiting if these ever show real contention,
 * and this is the evidence that argument would need. Exhaustions are not contention but failure —
 * a transition the bounded retry gave up on — and belong on an alert. Write-ahead depth and live
 * pack count are the two levels compaction exists to hold down, so they are how an operator sees
 * compaction falling behind before a restore does. The pack-cache counters are a ratio: a replica
 * whose hits collapse is paying object-store latency per read, which is the cold-start cost the
 * documentation promises to be honest about.
 *
 * <p>No meter here carries a marketplace, repository, snapshot or principal. The two levels are
 * summed across the repositories a replica has touched for exactly that reason.
 */
public class ObjectStoreMetrics implements MeterBinder {

    /** Conditional writes the store refused because the manifest had moved on. */
    public static final String CONFLICTS = "skills_gateway.storage.conditional_write.conflicts";

    /** Transition attempts made after such a refusal. */
    public static final String RETRIES = "skills_gateway.storage.conditional_write.retries";

    /** Transitions abandoned because the bounded retry ran out — a failure, not contention. */
    public static final String EXHAUSTIONS = "skills_gateway.storage.conditional_write.exhaustions";

    /** Freshness checks that found the reference map had moved under a cached copy. */
    public static final String REFRESHES = "skills_gateway.storage.manifest.refreshes";

    /** Write-ahead entries the manifest has not yet absorbed. */
    public static final String WAL_DEPTH = "skills_gateway.storage.wal.depth";

    /** Packs the manifests this replica has read say are live. */
    public static final String LIVE_PACKS = "skills_gateway.storage.packs.live";

    /** Pack opens served from the local cache. */
    public static final String PACK_CACHE_HITS = "skills_gateway.storage.pack_cache.hits";

    /** Packs fetched from the store into the local cache — the other half of the hit rate. */
    public static final String PACK_DOWNLOADS = "skills_gateway.storage.pack_cache.downloads";

    private final ObjectStoreStatistics statistics;

    public ObjectStoreMetrics(ObjectStoreStatistics statistics) {
        this.statistics = statistics;
    }

    @Override
    @Requirements({"GW_0116"})
    public void bindTo(MeterRegistry registry) {
        counter(registry, CONFLICTS, "conditional writes the store refused", ObjectStoreStatistics::conflicts);
        counter(registry, RETRIES, "transition attempts made after a refusal", ObjectStoreStatistics::retries);
        counter(registry, EXHAUSTIONS, "transitions the bounded retry gave up on", ObjectStoreStatistics::exhaustions);
        counter(registry, REFRESHES, "cached reference maps found stale", ObjectStoreStatistics::refreshes);
        counter(registry, PACK_CACHE_HITS, "pack opens served from local disk", ObjectStoreStatistics::packCacheHits);
        counter(registry, PACK_DOWNLOADS, "packs fetched from the store", ObjectStoreStatistics::packDownloads);
        level(
                registry,
                WAL_DEPTH,
                "write-ahead entries not yet folded into a manifest",
                ObjectStoreStatistics::walDepth);
        level(registry, LIVE_PACKS, "packs the read manifests say are live", ObjectStoreStatistics::livePacks);
    }

    private void counter(
            MeterRegistry registry,
            String name,
            String description,
            java.util.function.ToDoubleFunction<ObjectStoreStatistics> value) {
        FunctionCounter.builder(name, statistics, value)
                .description(description)
                .register(registry);
    }

    private void level(
            MeterRegistry registry,
            String name,
            String description,
            java.util.function.ToDoubleFunction<ObjectStoreStatistics> value) {
        Gauge.builder(name, statistics, value).description(description).register(registry);
    }
}
