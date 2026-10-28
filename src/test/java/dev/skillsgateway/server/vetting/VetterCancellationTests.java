package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A built-in vetter's pattern matching stops when its thread is interrupted (GW_VETTING_0062), which
 * is what the chain does to a vetter that outruns its time limit. Without that, cancelling the
 * future only sets a flag the regex engine never reads, and the match runs on.
 */
class VetterCancellationTests {

    /** One line on which the download-then-run pattern backtracks for far longer than any test. */
    private static final String PATHOLOGICAL_LINE = "curl -o x ".repeat(6_554);

    private static void assertStopsWhenInterrupted(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work, "vetter-under-test");
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(300);
        assertThat(thread.isAlive())
                .as("still matching before the interruption")
                .isTrue();
        thread.interrupt();
        thread.join(2_000);
        assertThat(thread.isAlive())
                .as("still matching two seconds after the interruption")
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0064"})
    void an_interrupted_scan_of_a_pathological_line_stops() throws Exception {
        assertStopsWhenInterrupted(() -> RuntimeFetch.scan(PATHOLOGICAL_LINE, true));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0064"})
    void an_interrupted_vetter_run_over_a_hook_script_stops() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                ".claude-plugin/marketplace.json",
                "{\"name\": \"m\", \"plugins\": [{\"name\": \"p\", \"source\": \"./p\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        files.put(
                "p/hooks/hooks.json",
                ("{\"hooks\": {\"SessionStart\": [{\"hooks\": [{\"type\": \"command\","
                                + " \"command\": \"${CLAUDE_PLUGIN_ROOT}/s.sh\"}]}]}}")
                        .getBytes(StandardCharsets.UTF_8));
        files.put("p/s.sh", ("#!/bin/sh\n" + PATHOLOGICAL_LINE + "\n").getBytes(StandardCharsets.UTF_8));
        InMemorySnapshot snapshot = new InMemorySnapshot(files);

        assertStopsWhenInterrupted(() -> new ExecutableSurfaceVetter().vet(snapshot));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0064"})
    void a_section_header_with_no_closing_bracket_is_read_in_linear_time() {
        String pyproject = "[" + " ".repeat(65_536) + "x\n[project]\ndependencies = [\"requests\"]\n";

        DependencyManifests.Declaration declared = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> DependencyManifests.declaration("pyproject.toml", pyproject));

        assertThat(declared)
                .as("the real section after the malformed header is still read")
                .isNotNull();
    }
}
