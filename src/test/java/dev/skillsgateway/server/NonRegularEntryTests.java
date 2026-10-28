package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A snapshot whose tree holds a symbolic link or a submodule entry is recorded rejected
 * (GW_INGEST_0070): the vetters read blobs at the paths they appear under, while a git client checks a
 * symbolic link out as a link, so what was vetted and what is installed could differ.
 */
class NonRegularEntryTests extends AbstractGatewayTest {

    private static final String HOOKS =
            "{\"hooks\": {\"SessionStart\": [{\"hooks\": [{\"type\": \"command\", \"command\": \"echo hi\"}]}]}}";

    private static void commit(Path dir, String message) throws Exception {
        GitResult add = git(dir, "add", "-A");
        assertThat(add.exitCode()).as(add.output()).isZero();
        GitResult commit = git(
                dir,
                "-c",
                "commit.gpgsign=false",
                "-c",
                "user.email=t@example.com",
                "-c",
                "user.name=t",
                "commit",
                "-q",
                "-m",
                message);
        assertThat(commit.exitCode()).as(commit.output()).isZero();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0070"})
    void a_symbolic_link_in_the_tree_rejects_the_snapshot_naming_the_path() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("shared/hooks.json", HOOKS));
        Files.createDirectories(upstream.resolve("plugins/hello/hooks"));
        Files.createSymbolicLink(
                upstream.resolve("plugins/hello/hooks/hooks.json"), Path.of("../../../shared/hooks.json"));
        commit(upstream, "link the hooks");

        Snapshot snapshot = registerAndIngest(uniqueName("symlink"), upstream).snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.violation())
                .contains("plugins/hello/hooks/hooks.json")
                .contains("symbolic link");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0070"})
    void a_submodule_entry_in_the_tree_rejects_the_snapshot_naming_the_path() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String someCommit = headSha(upstream);
        GitResult gitlink =
                git(upstream, "update-index", "--add", "--cacheinfo", "160000," + someCommit + ",plugins/hello/vendor");
        assertThat(gitlink.exitCode()).as(gitlink.output()).isZero();
        GitResult commit = git(
                upstream,
                "-c",
                "commit.gpgsign=false",
                "-c",
                "user.email=t@example.com",
                "-c",
                "user.name=t",
                "commit",
                "-q",
                "-m",
                "vendor a submodule");
        assertThat(commit.exitCode()).as(commit.output()).isZero();

        Snapshot snapshot = registerAndIngest(uniqueName("gitlink"), upstream).snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.violation()).contains("plugins/hello/vendor").contains("submodule");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0070"})
    void an_executable_file_is_still_held() throws Exception {
        Path upstream =
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/scripts/run.sh", "#!/bin/sh\necho ok\n"));
        GitResult chmod = git(upstream, "update-index", "--chmod=+x", "plugins/hello/scripts/run.sh");
        assertThat(chmod.exitCode()).as(chmod.output()).isZero();
        GitResult commit = git(
                upstream,
                "-c",
                "commit.gpgsign=false",
                "-c",
                "user.email=t@example.com",
                "-c",
                "user.name=t",
                "commit",
                "-q",
                "-m",
                "make it executable");
        assertThat(commit.exitCode()).as(commit.output()).isZero();

        Snapshot snapshot = registerAndIngest(uniqueName("execok"), upstream).snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
    }
}
