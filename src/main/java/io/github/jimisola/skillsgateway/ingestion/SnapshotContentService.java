package io.github.jimisola.skillsgateway.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

/**
 * Read-only inventory of what a snapshot contains (GW_0020): the plugins its manifest declares and
 * the skills found under each plugin's source tree. The future policy surface for limiting
 * individual plugins or skills hangs off this inventory.
 */
@Service
public class SnapshotContentService {

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitStorage storage;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;

    public SnapshotContentService(
            GitStorage storage, SnapshotRepository snapshotRepository, MarketplaceRepository marketplaceRepository) {
        this.storage = storage;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
    }

    @Schema(description = "A skill found under a plugin's source tree")
    public record SkillInfo(
            @Schema(description = "Skill directory name") String name,

            @Schema(description = "Path of the SKILL.md within the snapshot")
            String path) {}

    @Schema(description = "A plugin declared by the marketplace manifest")
    public record PluginContent(
            @Schema(description = "Plugin name from the manifest")
            String name,

            @Schema(description = "Plugin description from the manifest")
            String description,

            @Schema(description = "Relative source path inside the marketplace repository")
            String source,

            @Schema(description = "Skills found under <source>/skills/")
            List<SkillInfo> skills) {}

    @Schema(description = "What a snapshot ships: the manifest's plugins and their skills")
    public record SnapshotContent(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Upstream commit SHA") String sha,

            @Schema(description = "held, approved, or rejected")
            String state,

            @Schema(description = "Plugins declared by the manifest")
            List<PluginContent> plugins) {}

    @Requirements({"GW_0020"})
    public SnapshotContent content(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        try (Repository repo = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(snapshot.sha()));
            List<PluginContent> plugins = new ArrayList<>();
            byte[] manifest = readFile(repo, commit, MANIFEST_PATH);
            if (manifest != null) {
                JsonNode root = MAPPER.readTree(manifest);
                for (JsonNode plugin : root.path("plugins")) {
                    String name = plugin.path("name").asText(null);
                    String description = plugin.path("description").asText(null);
                    String source = plugin.path("source").isTextual()
                            ? plugin.get("source").asText()
                            : null;
                    List<SkillInfo> skills = source == null ? List.of() : skills(repo, commit, source);
                    plugins.add(new PluginContent(name, description, source, skills));
                }
            }
            return new SnapshotContent(snapshot.id(), snapshot.sha(), snapshot.state(), List.copyOf(plugins));
        } catch (IOException e) {
            throw new IngestionException(
                    "cannot read content of snapshot %d (%s)".formatted(snapshotId, snapshot.sha()), e);
        }
    }

    /** Skills are directories under {@code <source>/skills/} containing a SKILL.md. */
    private static List<SkillInfo> skills(Repository repo, RevCommit commit, String source) throws IOException {
        String prefix = normalize(source);
        String skillsRoot = prefix.isEmpty() ? "skills" : prefix + "/skills";
        List<SkillInfo> skills = new ArrayList<>();
        try (TreeWalk tree = new TreeWalk(repo)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (path.startsWith(skillsRoot + "/") && path.endsWith("/SKILL.md")) {
                    String rest = path.substring(skillsRoot.length() + 1);
                    int slash = rest.indexOf('/');
                    if (slash > 0 && rest.substring(slash).equals("/SKILL.md")) {
                        skills.add(new SkillInfo(rest.substring(0, slash), path));
                    }
                }
            }
        }
        return skills;
    }

    private static byte[] readFile(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tree == null) {
                return null;
            }
            return repo.open(tree.getObjectId(0)).getBytes();
        }
    }

    private static String normalize(String source) {
        String normalized = source;
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.replaceAll("/+$", "");
    }
}
