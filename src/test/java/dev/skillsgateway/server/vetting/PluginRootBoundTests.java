package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.skillsgateway.server.ingestion.ManifestPolicy;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The executable-surface vetter's work is bounded in the number of plugin roots and stops when
 * interrupted (GW_VETTING_0064): every root used to rescan every path, so cost was roots times entries.
 */
class PluginRootBoundTests {

    private static final byte[] PLUGIN = "{\"name\": \"p\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SKILL = "---\nname: s\ndescription: d\n---\nbody\n".getBytes(StandardCharsets.UTF_8);

    private static Map<String, byte[]> roots(int count, int filler) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            files.put("p%d/.claude-plugin/plugin.json".formatted(i), PLUGIN);
            files.put("p%d/skills/s/SKILL.md".formatted(i), SKILL);
        }
        for (int i = 0; i < filler; i++) {
            files.put("assets/f%d.txt".formatted(i), PLUGIN);
        }
        return files;
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0066"})
    void a_snapshot_with_more_plugin_roots_than_the_bound_fails_closed_quickly() {
        InMemorySnapshot snapshot = new InMemorySnapshot(roots(3_000, 0));

        Verdict verdict =
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> new ExecutableSurfaceVetter().vet(snapshot));

        assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
        assertThat(verdict.findings())
                .anySatisfy(finding -> assertThat(finding.id()).isEqualTo("too-many-plugin-roots"));
    }

    /** A manifest past the plugin bound, which only a snapshot ingested before the bound can hold, fails closed. */
    @Test
    @SVCs({"SVC_GW_VETTING_0066"})
    void a_manifest_with_more_plugins_than_the_bound_fails_closed() {
        StringBuilder plugins = new StringBuilder();
        for (int i = 0; i <= ManifestPolicy.MAX_PLUGINS; i++) {
            plugins.append(i == 0 ? "" : ",").append("{\"name\": \"p%d\", \"source\": \"./p\"}".formatted(i));
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                ".claude-plugin/marketplace.json",
                "{\"name\": \"m\", \"plugins\": [%s]}".formatted(plugins).getBytes(StandardCharsets.UTF_8));
        files.put("p/skills/s/SKILL.md", SKILL);

        Verdict verdict = new ExecutableSurfaceVetter().vet(new InMemorySnapshot(files));

        assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
        assertThat(verdict.findings()).anySatisfy(finding -> {
            assertThat(finding.id()).isEqualTo("too-many-plugin-roots");
            assertThat(finding.message()).contains("1001 plugins");
        });
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0066"})
    void an_interrupted_run_over_many_roots_stops() throws Exception {
        InMemorySnapshot snapshot = new InMemorySnapshot(roots(999, 200_000));
        InterruptedRun.assertStopsWhenInterrupted(() -> new ExecutableSurfaceVetter().vet(snapshot));
    }
}
