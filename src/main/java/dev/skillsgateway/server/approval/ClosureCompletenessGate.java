package dev.skillsgateway.server.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.ingestion.ManifestRewriter;
import dev.skillsgateway.server.ingestion.SnapshotClosure;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

/**
 * The closure-completeness assertion (GW_0165): before a snapshot is approved, its recorded
 * closure, its served manifest and its pinned tree must agree with one another.
 *
 * <p>Three views of the same fact, checked as a bijection. Let <i>M</i> be the plugins the served
 * manifest grafts (a {@code source} of {@code ./_plugins/<name>}), <i>C</i> the closure's members by
 * graft path, and <i>T</i> the entries of the pinned commit's top-level {@code _plugins} tree.
 * Complete means every <i>M</i> is a <i>C</i>, every <i>C</i> is an <i>M</i> and a <i>T</i> with the
 * recorded tree id, and every <i>T</i> is a <i>C</i>. A local-only snapshot is complete trivially:
 * all three sets are empty.
 *
 * <p>Nothing in the gateway can produce an incomplete closure — ingestion writes the closure in
 * the snapshot's transaction — so this never fires on a snapshot the gateway made. It exists so
 * that nothing ever can publish one, whatever produces it: a future code path, a schema tool, a
 * restore from backup. It reads only trees reachable from the pinned commit, never the external
 * commit a member names, because that object may legitimately have been collected.
 */
@Component
public class ClosureCompletenessGate {

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final String SOURCE_PREFIX = "./" + ManifestRewriter.GRAFT_DIR + "/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitStorage storage;
    private final SnapshotClosureRepository closures;

    public ClosureCompletenessGate(GitStorage storage, SnapshotClosureRepository closures) {
        this.storage = storage;
        this.closures = closures;
    }

    /** Raises when the snapshot's closure does not describe the commit it pins. */
    @Requirements({"GW_0165"})
    public void require(Snapshot snapshot, Marketplace marketplace) {
        List<String> discrepancies = discrepancies(snapshot, marketplace);
        if (!discrepancies.isEmpty()) {
            throw new ClosureIncompleteException(snapshot.id(), discrepancies);
        }
    }

    /** Every way the three views disagree; empty when the closure is complete. */
    List<String> discrepancies(Snapshot snapshot, Marketplace marketplace) {
        List<String> discrepancies = new ArrayList<>();
        Map<String, SnapshotClosure.Member> members = new LinkedHashMap<>();
        closures.findBySnapshot(snapshot.id()).ifPresent(closure -> {
            for (SnapshotClosure.Member member : closure.members()) {
                if (members.put(member.graftPath(), member) != null) {
                    discrepancies.add("the closure records two members at " + member.graftPath());
                }
            }
        });
        try (Repository repository = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(snapshot.sha()));
            Map<String, String> grafted = manifestGrafts(repository, commit, discrepancies);
            Map<String, ObjectId> trees = graftTrees(repository, commit, discrepancies);

            for (Map.Entry<String, String> graft : grafted.entrySet()) {
                if (!members.containsKey(graft.getKey())) {
                    discrepancies.add("the served manifest declares plugin '%s' at %s, which the recorded closure"
                                    .formatted(graft.getValue(), graft.getKey())
                            + " does not contain");
                }
            }
            for (SnapshotClosure.Member member : members.values()) {
                String path = member.graftPath();
                if (!grafted.containsKey(path)) {
                    discrepancies.add("the closure records plugin '%s' at %s, which the served manifest does not"
                                    .formatted(member.pluginName(), path)
                            + " declare");
                }
                if (!ObjectId.isId(member.resolvedSha())) {
                    discrepancies.add("the closure member for plugin '%s' records '%s' as its resolved commit,"
                                    .formatted(member.pluginName(), member.resolvedSha())
                            + " which is not a commit id");
                }
                ObjectId tree = trees.get(path);
                if (tree == null) {
                    discrepancies.add("the pinned commit holds no tree at %s, which the closure records for plugin '%s'"
                            .formatted(path, member.pluginName()));
                } else if (!tree.name().equals(member.treeSha())) {
                    discrepancies.add("the tree at %s in the pinned commit is %s, but the closure records %s for"
                                    .formatted(path, tree.name(), member.treeSha())
                            + " plugin '%s'".formatted(member.pluginName()));
                }
            }
            for (String path : trees.keySet()) {
                if (!members.containsKey(path)) {
                    discrepancies.add(
                            "the pinned commit holds %s under the reserved directory, which no closure".formatted(path)
                                    + " member accounts for");
                }
            }
        } catch (IOException | RuntimeException e) {
            // Fail closed: a commit that cannot be read cannot be shown complete.
            discrepancies.add("the pinned commit could not be read: " + e.getMessage());
        }
        return discrepancies;
    }

    /** Graft path → plugin name, for every manifest plugin whose source is under the reserved directory. */
    private static Map<String, String> manifestGrafts(Repository repository, RevCommit commit, List<String> problems)
            throws IOException {
        Map<String, String> grafted = new LinkedHashMap<>();
        try (TreeWalk manifest = TreeWalk.forPath(repository, MANIFEST_PATH, commit.getTree())) {
            if (manifest == null) {
                problems.add("the pinned commit has no " + MANIFEST_PATH);
                return grafted;
            }
            JsonNode root =
                    MAPPER.readTree(repository.open(manifest.getObjectId(0)).getBytes());
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) {
                return grafted;
            }
            for (JsonNode plugin : plugins) {
                JsonNode source = plugin.get("source");
                if (source == null || !source.isTextual() || !source.asText().startsWith(SOURCE_PREFIX)) {
                    continue;
                }
                String path = source.asText().substring(2);
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                JsonNode name = plugin.get("name");
                grafted.put(path, name == null || !name.isTextual() ? "" : name.asText());
            }
        }
        return grafted;
    }

    /** Graft path → tree id, for every entry directly under the reserved directory. */
    private static Map<String, ObjectId> graftTrees(Repository repository, RevCommit commit, List<String> problems)
            throws IOException {
        Map<String, ObjectId> trees = new LinkedHashMap<>();
        try (TreeWalk reserved = TreeWalk.forPath(repository, ManifestRewriter.GRAFT_DIR, commit.getTree())) {
            if (reserved == null) {
                return trees;
            }
            if (!reserved.isSubtree()) {
                problems.add("the pinned commit holds a file named %s where the reserved directory would be"
                        .formatted(ManifestRewriter.GRAFT_DIR));
                return trees;
            }
            try (TreeWalk entries = new TreeWalk(repository)) {
                entries.addTree(reserved.getObjectId(0));
                entries.setRecursive(false);
                while (entries.next()) {
                    String path = ManifestRewriter.GRAFT_DIR + "/" + entries.getNameString();
                    if (!entries.isSubtree()) {
                        problems.add("the pinned commit holds a file at %s, where only grafted trees may be"
                                .formatted(path));
                        continue;
                    }
                    trees.put(path, entries.getObjectId(0));
                }
            }
        }
        return trees;
    }
}
