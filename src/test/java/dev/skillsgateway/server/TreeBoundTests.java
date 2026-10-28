package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.skillsgateway.server.persistence.Snapshot;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

/**
 * A snapshot's tree is walked only within a bound on its entries and depth (GW_INGEST_0072). Git stores
 * a subtree once however often it is referenced, so a handful of tree objects can describe a tree
 * with a hundred million paths; walking it recursively visits every one.
 */
class TreeBoundTests extends AbstractGatewayTest {

    /**
     * Adds to the upstream's main a directory {@code bomb/} that fans out ten ways over eight levels:
     * nine tree objects and one blob, 10^8 paths.
     */
    static String commitTreeBomb(Path upstream) throws IOException {
        try (Git git = Git.open(upstream.toFile());
                ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "x".getBytes(StandardCharsets.UTF_8));
            TreeFormatter leaf = new TreeFormatter();
            leaf.append("f", FileMode.REGULAR_FILE, blob);
            ObjectId level = inserter.insert(leaf);
            for (int depth = 0; depth < 8; depth++) {
                TreeFormatter fan = new TreeFormatter();
                for (int i = 0; i < 10; i++) {
                    fan.append("d" + i, FileMode.TREE, level);
                }
                level = inserter.insert(fan);
            }
            ObjectId head = repository.resolve(Constants.HEAD);
            RevCommit parent;
            try (RevWalk walk = new RevWalk(repository)) {
                parent = walk.parseCommit(head);
            }
            TreeFormatter root = new TreeFormatter();
            try (TreeWalk existing = new TreeWalk(repository)) {
                existing.addTree(parent.getTree());
                boolean added = false;
                while (existing.next()) {
                    if (!added && existing.getNameString().compareTo("bomb") > 0) {
                        root.append("bomb", FileMode.TREE, level);
                        added = true;
                    }
                    root.append(existing.getNameString(), existing.getFileMode(0), existing.getObjectId(0));
                }
                if (!added) {
                    root.append("bomb", FileMode.TREE, level);
                }
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(root));
            commit.setParentId(head);
            PersonIdent who = new PersonIdent("t", "t@example.com");
            commit.setAuthor(who);
            commit.setCommitter(who);
            commit.setMessage("a small tree with many paths");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            org.eclipse.jgit.lib.RefUpdate update = repository.updateRef("refs/heads/main");
            update.setNewObjectId(id);
            update.forceUpdate();
            return id.name();
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0072"})
    void a_tree_with_too_many_paths_rejects_the_snapshot_quickly() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        commitTreeBomb(upstream);

        Snapshot snapshot = assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> registerAndIngest(uniqueName("treebomb"), upstream).snapshot());

        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.violation()).contains("entries");
    }
}
