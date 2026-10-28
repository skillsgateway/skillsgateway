package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The vetting index refuses a tree past the entry bound instead of expanding it (GW_INGEST_0072), so a
 * snapshot that reached vetting some other way still records a blocking error rather than exhausting
 * the heap.
 */
class TreeBoundIndexTests {

    @TempDir
    Path work;

    @Test
    @SVCs({"SVC_GW_INGEST_0072"})
    void the_vetting_index_refuses_a_tree_with_too_many_paths_quickly() throws Exception {
        try (Git git = Git.init().setDirectory(work.toFile()).call();
                ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            // Empty leaves: a recursive walk yields no file at all here, so a cap on files alone never
            // fires while the walk still visits every one of the 10^8 subtrees.
            ObjectId level = inserter.insert(new TreeFormatter());
            for (int depth = 0; depth < 8; depth++) {
                TreeFormatter fan = new TreeFormatter();
                for (int i = 0; i < 10; i++) {
                    fan.append("d" + i, FileMode.TREE, level);
                }
                level = inserter.insert(fan);
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(level);
            PersonIdent who = new PersonIdent("t", "t@example.com");
            commit.setAuthor(who);
            commit.setCommitter(who);
            commit.setMessage("a small tree with many paths");
            String sha = inserter.insert(commit).name();
            inserter.flush();

            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> assertThatThrownBy(() -> new QuarantineSnapshot(1, "m", sha, 1 << 20, 1 << 20, repository))
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("entries"));
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0072"})
    void the_vetting_index_refuses_a_tree_deeper_than_the_bound() throws Exception {
        try (Git git = Git.init().setDirectory(work.resolve("deep").toFile()).call();
                ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "x".getBytes(StandardCharsets.UTF_8));
            TreeFormatter leaf = new TreeFormatter();
            leaf.append("f", FileMode.REGULAR_FILE, blob);
            ObjectId level = inserter.insert(leaf);
            for (int depth = 0; depth < 40; depth++) {
                TreeFormatter nest = new TreeFormatter();
                nest.append("d", FileMode.TREE, level);
                level = inserter.insert(nest);
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(level);
            PersonIdent who = new PersonIdent("t", "t@example.com");
            commit.setAuthor(who);
            commit.setCommitter(who);
            commit.setMessage("forty directories deep");
            String sha = inserter.insert(commit).name();
            inserter.flush();

            assertThatThrownBy(() -> new QuarantineSnapshot(1, "m", sha, 1 << 20, 1 << 20, repository))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("deep");
        }
    }
}
