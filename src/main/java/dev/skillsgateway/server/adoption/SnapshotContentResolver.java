package dev.skillsgateway.server.adoption;

import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.ingestion.SnapshotContentService.PluginContent;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What a served commit contains, by {@code (marketplace, sha)}, for the presence report
 * (GW_OBSERVABILITY_0006). A pinned commit's content is immutable, so a resolved answer is cached
 * for the life of the process and evicted only by size. An unresolvable answer is not cached: a
 * storage outage must not pin "unknown" onto a commit that is still there.
 */
@Component
public class SnapshotContentResolver {

    /** Counter of cache lookups, tagged {@code result=hit|miss}. */
    public static final String CACHE = "skills_gateway.adoption.presence_cache";

    static final int MAX_ENTRIES = 1024;

    private final SnapshotContentService contentService;
    private final Counter hits;
    private final Counter misses;
    private final Map<String, List<PluginContent>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<PluginContent>> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public SnapshotContentResolver(SnapshotContentService contentService, MeterRegistry meters) {
        this.contentService = contentService;
        this.hits = Counter.builder(CACHE).tag("result", "hit").register(meters);
        this.misses = Counter.builder(CACHE).tag("result", "miss").register(meters);
    }

    /** The commit's plugins and skills, or empty when its content cannot be resolved. */
    @Requirements({"GW_OBSERVABILITY_0006"})
    public Optional<List<PluginContent>> resolve(String marketplace, String sha) {
        // NUL cannot occur in a marketplace name or a hex SHA.
        String key = marketplace + '\0' + sha;
        synchronized (cache) {
            List<PluginContent> cached = cache.get(key);
            if (cached != null) {
                hits.increment();
                return Optional.of(cached);
            }
        }
        misses.increment();
        Optional<List<PluginContent>> resolved = contentService.skillsAt(marketplace, sha);
        resolved.ifPresent(plugins -> {
            synchronized (cache) {
                cache.put(key, plugins);
            }
        });
        return resolved;
    }
}
