package dev.skillsgateway.server.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The diff summary cache holds a bounded number of entries and drops the least recently used. */
class SummaryCacheTests {

    @Test
    void holds_at_most_its_capacity_and_evicts_the_least_recently_used() {
        Map<String, Integer> cache = SnapshotPreviewService.lru(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);

        assertThat(cache).hasSize(2).containsKeys("a", "c").doesNotContainKey("b");
    }
}
