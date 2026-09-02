package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>The same inventory diffed against the marketplace's last approved snapshot (GW_0153) is what
 * a reviewer actually decides on — the inventory says what a snapshot ships, the diff says what
 * approving it would add to what the organisation already accepted.
 */
@Service
public class SnapshotContentService {

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Diff status vocabulary; {@code moved} and {@code unchanged} apply to skills only. */
    private static final String ADDED = "added";

    private static final String REMOVED = "removed";
    private static final String CHANGED = "changed";
    private static final String MOVED = "moved";
    private static final String UNCHANGED = "unchanged";

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

    @Schema(description = "One skill of the snapshot or of the baseline, and how it differs")
    public record SkillDiff(
            @Schema(description = "Skill directory name") String name,

            @Schema(description = "Path of the SKILL.md; the baseline's path for a removed skill")
            String path,

            @Schema(
                    description = "How the skill differs from the baseline",
                    allowableValues = {"added", "removed", "changed", "moved", "unchanged"})
            String status,

            @Schema(description = "Plugin the skill was declared under in the baseline, when it moved; else null")
            String movedFromPlugin) {}

    @Schema(description = "One plugin of the snapshot or of the baseline, and how it differs")
    public record PluginDiff(
            @Schema(description = "Plugin name") String name,

            @Schema(description = "Plugin description; the baseline's for a removed plugin")
            String description,

            @Schema(description = "Relative source path; the baseline's for a removed plugin")
            String source,

            @Schema(
                    description = "How the plugin differs from the baseline. A plugin is changed when any skill"
                            + " under it differs or when its manifest entry does",
                    allowableValues = {"added", "removed", "changed", "unchanged"})
            String status,

            @Schema(description = "Every skill of this plugin on either side, each with its own status")
            List<SkillDiff> skills) {}

    @Schema(description = "How many skills fall into each status")
    public record DiffSummary(
            @Schema(description = "Skills the baseline does not have")
            int added,

            @Schema(description = "Skills the snapshot no longer has")
            int removed,

            @Schema(description = "Skills whose content differs")
            int changed,

            @Schema(description = "Skills that kept their content and changed plugin")
            int moved,

            @Schema(description = "Skills that are identical on both sides")
            int unchanged) {}

    @Schema(description = "A snapshot's content inventory against the marketplace's last approved snapshot")
    public record ContentDiff(
            @Schema(description = "Snapshot id") long snapshotId,
            @Schema(description = "Upstream commit SHA") String sha,

            @Schema(description = "held, approved, rejected, or revoked")
            String state,

            @Schema(description = "Id of the approved snapshot compared against, or null when none is approved")
            Long baselineSnapshotId,

            @Schema(description = "Commit SHA compared against, or null when no snapshot is approved")
            String baselineSha,

            @Schema(description = "Every plugin on either side, in manifest order, removed plugins last")
            List<PluginDiff> plugins,

            @Schema(description = "Skill counts per status") DiffSummary summary) {}

    @Requirements({"GW_0020"})
    public SnapshotContent content(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplace(snapshot);
        try (Repository repo = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(snapshot.sha()));
            return new SnapshotContent(snapshot.id(), snapshot.sha(), snapshot.state(), plugins(repo, commit));
        } catch (IOException e) {
            throw new IngestionException(
                    "cannot read content of snapshot %d (%s)".formatted(snapshotId, snapshot.sha()), e);
        }
    }

    /** The manifest's plugins with their skills, at one commit. */
    private static List<PluginContent> plugins(Repository repo, RevCommit commit) throws IOException {
        byte[] manifest = readFile(repo, commit, MANIFEST_PATH);
        if (manifest == null) {
            return List.of();
        }
        List<PluginContent> plugins = new ArrayList<>();
        JsonNode root = MAPPER.readTree(manifest);
        for (JsonNode plugin : root.path("plugins")) {
            String name = plugin.path("name").asText(null);
            String description = plugin.path("description").asText(null);
            String source =
                    plugin.path("source").isTextual() ? plugin.get("source").asText() : null;
            List<SkillInfo> skills = source == null ? List.of() : skills(repo, commit, source);
            plugins.add(new PluginContent(name, description, source, skills));
        }
        return List.copyOf(plugins);
    }

    /**
     * The snapshot's inventory against the marketplace's last approved snapshot (GW_0153).
     *
     * <p>Both commits live in the same quarantine repository, so the whole comparison is one
     * repository handle and no fetch. A skill counts as changed when the git tree object of its
     * directory differs — a tree id is a recursive hash of everything under it, so an edited
     * helper script beside an untouched SKILL.md is a change, which is the answer a reviewer needs
     * even though the cheaper SKILL.md-only comparison would have said otherwise.
     *
     * <p>With nothing approved yet the answer says so: a null baseline and everything added,
     * rather than a diff that quietly looks like a review of nothing.
     */
    @Requirements({"GW_0153"})
    public ContentDiff diff(long snapshotId) {
        Snapshot snapshot =
                snapshotRepository.findById(snapshotId).orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        Marketplace marketplace = marketplace(snapshot);
        Optional<Snapshot> baseline =
                snapshotRepository.latestApprovedByMarketplace(snapshot.marketplaceId(), snapshot.id());
        try (Repository repo = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repo)) {
            Side current = side(repo, walk.parseCommit(ObjectId.fromString(snapshot.sha())));
            Side base = baseline.isPresent()
                    ? side(
                            repo,
                            walk.parseCommit(ObjectId.fromString(baseline.get().sha())))
                    : new Side(List.of(), Map.of());
            return compare(snapshot, baseline.orElse(null), current, base);
        } catch (IOException e) {
            throw new IngestionException(
                    "cannot diff content of snapshot %d (%s)".formatted(snapshotId, snapshot.sha()), e);
        }
    }

    /** One commit's inventory, with the tree object of every skill directory keyed plugin/skill. */
    private record Side(List<PluginContent> plugins, Map<String, ObjectId> skillTrees) {}

    private static Side side(Repository repo, RevCommit commit) throws IOException {
        List<PluginContent> plugins = plugins(repo, commit);
        Map<String, ObjectId> trees = new HashMap<>();
        for (PluginContent plugin : plugins) {
            for (SkillInfo skill : plugin.skills()) {
                String directory = skill.path().substring(0, skill.path().lastIndexOf('/'));
                try (TreeWalk tree = TreeWalk.forPath(repo, directory, commit.getTree())) {
                    if (tree != null) {
                        trees.put(key(plugin.name(), skill.name()), tree.getObjectId(0));
                    }
                }
            }
        }
        return new Side(plugins, Map.copyOf(trees));
    }

    private static ContentDiff compare(Snapshot snapshot, Snapshot baseline, Side current, Side base) {
        Map<String, SkillInfo> baseSkills = skillsByKey(base);
        Map<String, SkillInfo> currentSkills = skillsByKey(current);
        Map<String, String> movedFrom = new HashMap<>();
        Set<String> movedAway = new HashSet<>();
        detectMoves(current, base, currentSkills, baseSkills, movedFrom, movedAway);

        List<PluginDiff> plugins = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (PluginContent plugin : current.plugins()) {
            PluginContent previous = named(base.plugins(), plugin.name());
            List<SkillDiff> skills = new ArrayList<>();
            for (SkillInfo skill : plugin.skills()) {
                skills.add(classify(plugin.name(), skill, current, base, movedFrom));
            }
            if (previous != null) {
                for (SkillInfo gone : previous.skills()) {
                    String key = key(previous.name(), gone.name());
                    if (!currentSkills.containsKey(key) && !movedAway.contains(key)) {
                        skills.add(new SkillDiff(gone.name(), gone.path(), REMOVED, null));
                    }
                }
            }
            String status = previous == null ? ADDED : pluginStatus(plugin, previous, skills);
            plugins.add(
                    new PluginDiff(plugin.name(), plugin.description(), plugin.source(), status, List.copyOf(skills)));
            count(counts, skills);
        }
        for (PluginContent gone : base.plugins()) {
            if (named(current.plugins(), gone.name()) != null) {
                continue;
            }
            List<SkillDiff> skills = gone.skills().stream()
                    .filter(skill -> !movedAway.contains(key(gone.name(), skill.name())))
                    .map(skill -> new SkillDiff(skill.name(), skill.path(), REMOVED, null))
                    .toList();
            plugins.add(new PluginDiff(gone.name(), gone.description(), gone.source(), REMOVED, skills));
            count(counts, skills);
        }
        return new ContentDiff(
                snapshot.id(),
                snapshot.sha(),
                snapshot.state(),
                baseline == null ? null : baseline.id(),
                baseline == null ? null : baseline.sha(),
                List.copyOf(plugins),
                new DiffSummary(
                        counts.getOrDefault(ADDED, 0),
                        counts.getOrDefault(REMOVED, 0),
                        counts.getOrDefault(CHANGED, 0),
                        counts.getOrDefault(MOVED, 0),
                        counts.getOrDefault(UNCHANGED, 0)));
    }

    /**
     * A skill that a plugin no longer declares and another plugin now does is one skill that
     * moved, not a deletion and an unrelated addition — the shape a manifest reorganisation takes.
     * Matched by name across plugins, which is the granularity the manifest itself works at, and
     * only when exactly one plugin gave the name up: an ambiguous match is left as the plain
     * add-and-remove pair rather than guessed at.
     */
    private static void detectMoves(
            Side current,
            Side base,
            Map<String, SkillInfo> currentSkills,
            Map<String, SkillInfo> baseSkills,
            Map<String, String> movedFrom,
            Set<String> movedAway) {
        for (PluginContent plugin : current.plugins()) {
            for (SkillInfo skill : plugin.skills()) {
                String key = key(plugin.name(), skill.name());
                if (baseSkills.containsKey(key)) {
                    continue;
                }
                List<String> donors = base.plugins().stream()
                        .filter(candidate -> candidate.skills().stream()
                                .anyMatch(previous -> previous.name().equals(skill.name())))
                        .map(PluginContent::name)
                        .filter(donor -> !currentSkills.containsKey(key(donor, skill.name())))
                        .filter(donor -> !movedAway.contains(key(donor, skill.name())))
                        .toList();
                if (donors.size() == 1) {
                    movedFrom.put(key, donors.getFirst());
                    movedAway.add(key(donors.getFirst(), skill.name()));
                }
            }
        }
    }

    private static SkillDiff classify(
            String plugin, SkillInfo skill, Side current, Side base, Map<String, String> movedFrom) {
        String key = key(plugin, skill.name());
        String donor = movedFrom.get(key);
        String baselineKey = donor == null ? key : key(donor, skill.name());
        if (donor == null && !base.skillTrees().containsKey(baselineKey)) {
            return new SkillDiff(skill.name(), skill.path(), ADDED, null);
        }
        boolean identical =
                Objects.equals(current.skillTrees().get(key), base.skillTrees().get(baselineKey));
        if (donor != null) {
            return new SkillDiff(skill.name(), skill.path(), identical ? MOVED : CHANGED, donor);
        }
        return new SkillDiff(skill.name(), skill.path(), identical ? UNCHANGED : CHANGED, null);
    }

    /** A plugin changed when anything under it did, or when its own manifest entry did. */
    private static String pluginStatus(PluginContent plugin, PluginContent previous, List<SkillDiff> skills) {
        boolean manifestChanged = !Objects.equals(plugin.description(), previous.description())
                || !Objects.equals(plugin.source(), previous.source());
        boolean skillChanged = skills.stream().anyMatch(skill -> !UNCHANGED.equals(skill.status()));
        return manifestChanged || skillChanged ? CHANGED : UNCHANGED;
    }

    private static Map<String, SkillInfo> skillsByKey(Side side) {
        Map<String, SkillInfo> skills = new HashMap<>();
        for (PluginContent plugin : side.plugins()) {
            for (SkillInfo skill : plugin.skills()) {
                skills.put(key(plugin.name(), skill.name()), skill);
            }
        }
        return skills;
    }

    private static void count(Map<String, Integer> counts, List<SkillDiff> skills) {
        for (SkillDiff skill : skills) {
            counts.merge(skill.status(), 1, Integer::sum);
        }
    }

    private static PluginContent named(List<PluginContent> plugins, String name) {
        return plugins.stream()
                .filter(plugin -> Objects.equals(plugin.name(), name))
                .findFirst()
                .orElse(null);
    }

    /** Keys the per-side skill maps; NUL cannot occur in a manifest name or a path component. */
    private static String key(String plugin, String skill) {
        return plugin + '\0' + skill;
    }

    private Marketplace marketplace(Snapshot snapshot) {
        return marketplaceRepository
                .findById(snapshot.marketplaceId())
                .orElseThrow(() -> new SnapshotNotFoundException(snapshot.id()));
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
