package dev.skillsgateway.server.policy;

import dev.skillsgateway.server.ingestion.IngestionException;
import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

/**
 * Builds the facts a policy expression evaluates over (GW_0090): the snapshot's metadata, its
 * file inventory, and its plugin/skill inventory with each skill's declared tools parsed from
 * SKILL.md frontmatter. Everything is read from exactly the pinned commit's tree through JGit
 * object walks — never the filesystem, so there is no traversal surface, and quarantine content
 * never leaves the evaluation.
 *
 * <p>Fail-closed by construction: an inventory over its bound, an unreadable or oversized
 * SKILL.md, or malformed frontmatter raises {@link PolicyEvaluationException} — content a rule
 * cannot see must never slip past it, so a truncated inventory is an error, not a smaller list.
 */
@Service
public class SnapshotFactsService {

    /** Fail-closed inventory bound: beyond it facts cannot be trusted, so they cannot be built. */
    static final int MAX_FILES = 20_000;

    /** A SKILL.md larger than this cannot have its frontmatter trusted; facts fail, gate denies. */
    static final int MAX_SKILL_MD_BYTES = 256 * 1024;

    private final GitStorage storage;
    private final SnapshotContentService contentService;

    public SnapshotFactsService(GitStorage storage, SnapshotContentService contentService) {
        this.storage = storage;
        this.contentService = contentService;
    }

    /** The variables of one evaluation: {@code snapshot}, {@code files}, {@code plugins}, {@code skills}. */
    @Requirements({"GW_0090"})
    public Map<String, Object> build(Snapshot snapshot, Marketplace marketplace) {
        Map<String, Object> snapshotFacts = new HashMap<>();
        snapshotFacts.put("id", snapshot.id());
        snapshotFacts.put("sha", snapshot.sha());
        snapshotFacts.put("marketplace", marketplace.name());
        snapshotFacts.put("state", snapshot.state());

        List<Map<String, Object>> plugins = new ArrayList<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        SnapshotContentService.SnapshotContent content;
        try {
            content = contentService.content(snapshot.id());
        } catch (IngestionException e) {
            throw new PolicyEvaluationException("snapshot inventory could not be read: " + e.getMessage(), e);
        }

        try (Repository repo = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(snapshot.sha()));
            List<Map<String, Object>> files = files(repo, commit);
            for (SnapshotContentService.PluginContent plugin : content.plugins()) {
                Map<String, Object> pluginFacts = new HashMap<>();
                pluginFacts.put("name", Objects.toString(plugin.name(), ""));
                pluginFacts.put("description", Objects.toString(plugin.description(), ""));
                pluginFacts.put("source", Objects.toString(plugin.source(), ""));
                plugins.add(pluginFacts);
                for (SnapshotContentService.SkillInfo skill : plugin.skills()) {
                    Map<String, Object> skillFacts = new HashMap<>();
                    skillFacts.put("name", skill.name());
                    skillFacts.put("path", skill.path());
                    skillFacts.put("plugin", Objects.toString(plugin.name(), ""));
                    skillFacts.put("tools", SkillFrontmatter.tools(skillMarkdown(repo, commit, skill.path())));
                    skills.add(skillFacts);
                }
            }
            Map<String, Object> facts = new HashMap<>();
            facts.put("snapshot", snapshotFacts);
            facts.put("files", files);
            facts.put("plugins", plugins);
            facts.put("skills", skills);
            return facts;
        } catch (IOException e) {
            throw new PolicyEvaluationException(
                    "facts for snapshot %d could not be built: %s".formatted(snapshot.id(), e.getMessage()), e);
        }
    }

    private static List<Map<String, Object>> files(Repository repo, RevCommit commit) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (ObjectReader reader = repo.newObjectReader();
                TreeWalk tree = new TreeWalk(repo)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (files.size() >= MAX_FILES) {
                    throw new PolicyEvaluationException(
                            "file inventory exceeds %d entries; facts cannot be built".formatted(MAX_FILES));
                }
                Map<String, Object> file = new HashMap<>();
                file.put("path", tree.getPathString());
                file.put("size", reader.getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB));
                files.add(file);
            }
        }
        return files;
    }

    private static String skillMarkdown(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tree == null) {
                throw new PolicyEvaluationException("skill file %s vanished from the pinned tree".formatted(path));
            }
            ObjectLoader loader = repo.open(tree.getObjectId(0), Constants.OBJ_BLOB);
            if (loader.getSize() > MAX_SKILL_MD_BYTES) {
                throw new PolicyEvaluationException(
                        "%s exceeds %d bytes; its frontmatter cannot be trusted".formatted(path, MAX_SKILL_MD_BYTES));
            }
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }
}
