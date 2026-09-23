package dev.skillsgateway.server.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.ingestion.IngestionException;
import dev.skillsgateway.server.ingestion.SnapshotClosure;
import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotClosureRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the facts a policy expression evaluates over (GW_APPROVAL_0007): the snapshot's metadata, its
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

    /** Where a manifest declares its plugins; a plugin name's location is a line of this file. */
    public static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private static final Logger log = LoggerFactory.getLogger(SnapshotFactsService.class);

    /** Integers read back as {@code Long}, the type {@link #build} puts in and CEL's int is. */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS);

    private static final TypeReference<Map<String, Object>> FACTS_TYPE = new TypeReference<>() {};

    private final GitStorage storage;
    private final SnapshotContentService contentService;
    private final SnapshotClosureRepository closures;
    private final SnapshotFactsRepository repository;

    public SnapshotFactsService(
            GitStorage storage,
            SnapshotContentService contentService,
            SnapshotClosureRepository closures,
            SnapshotFactsRepository repository) {
        this.storage = storage;
        this.contentService = contentService;
        this.closures = closures;
        this.repository = repository;
    }

    /**
     * Records the snapshot's facts and plugin-name index, once (GW_INGEST_0036). The name index is
     * built from the manifest alone and is what the record's existence vouches for; the full facts
     * are stored when they can be built and left null when they cannot, so a failure is never
     * recorded as the snapshot's facts. Never throws: at ingestion a failure must not fail the
     * ingestion, and at approval the caller asks {@link #indexed} for the answer that matters.
     *
     * @return whether the snapshot has a record after this call
     */
    @Requirements({"GW_INGEST_0036"})
    public boolean record(Snapshot snapshot, Marketplace marketplace) {
        if (repository.indexed(snapshot.id())) {
            return true;
        }
        List<SnapshotFactsRepository.PluginName> plugins;
        try {
            plugins = pluginNames(snapshot, marketplace);
        } catch (IOException | RuntimeException e) {
            log.warn("plugin names of snapshot {} could not be indexed: {}", snapshot.id(), e.getMessage());
            return false;
        }
        String json = null;
        try {
            Map<String, Object> facts = build(snapshot, marketplace);
            Map<String, Object> snapshotFacts = new HashMap<>(snapshotMap(facts));
            snapshotFacts.remove("state");
            Map<String, Object> stored = new HashMap<>(facts);
            stored.put("snapshot", snapshotFacts);
            json = MAPPER.writeValueAsString(stored);
        } catch (PolicyEvaluationException | JsonProcessingException e) {
            log.info(
                    "facts of snapshot {} were not recorded and will be built when read: {}",
                    snapshot.id(),
                    e.getMessage());
        }
        repository.insertOnce(snapshot.id(), json, plugins);
        return true;
    }

    /** Whether the snapshot's plugin-name index is recorded. */
    public boolean indexed(long snapshotId) {
        return repository.indexed(snapshotId);
    }

    /**
     * The facts a rule evaluates over (GW_INGEST_0036): the recorded ones with the snapshot's current
     * state supplied, or — where none were recorded — built now, exactly as before they were
     * recorded, including the same {@link PolicyEvaluationException}.
     */
    @Requirements({"GW_INGEST_0036"})
    public Map<String, Object> load(Snapshot snapshot, Marketplace marketplace) {
        String json = repository
                .find(snapshot.id())
                .map(SnapshotFactsRepository.Recorded::factsJson)
                .orElse(null);
        if (json == null) {
            return build(snapshot, marketplace);
        }
        try {
            Map<String, Object> facts = MAPPER.readValue(json, FACTS_TYPE);
            Map<String, Object> snapshotFacts = new HashMap<>(snapshotMap(facts));
            snapshotFacts.put("state", snapshot.state());
            facts.put("snapshot", snapshotFacts);
            return facts;
        } catch (JsonProcessingException e) {
            throw new PolicyEvaluationException(
                    "recorded facts of snapshot %d are unreadable".formatted(snapshot.id()), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotMap(Map<String, Object> facts) {
        return (Map<String, Object>) facts.get("snapshot");
    }

    /**
     * Each plugin the manifest names, located at the manifest line that declares its name — a
     * location a waiver's path scope can match. Plugins without a name are not names and are left
     * out.
     */
    private List<SnapshotFactsRepository.PluginName> pluginNames(Snapshot snapshot, Marketplace marketplace)
            throws IOException {
        List<SnapshotContentService.PluginContent> plugins =
                contentService.content(snapshot.id()).plugins();
        Map<Integer, Integer> lines;
        try (Repository repo = storage.quarantine(marketplace.name());
                RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(snapshot.sha()));
            byte[] manifest = fileBytes(repo, commit, MANIFEST_PATH);
            lines = manifest == null ? Map.of() : pluginNameLines(manifest);
        }
        List<SnapshotFactsRepository.PluginName> names = new ArrayList<>();
        for (int i = 0; i < plugins.size(); i++) {
            String name = plugins.get(i).name();
            if (name == null || name.isBlank()) {
                continue;
            }
            Integer line = lines.get(i);
            names.add(new SnapshotFactsRepository.PluginName(
                    name, line == null ? MANIFEST_PATH : MANIFEST_PATH + ":" + line));
        }
        return names;
    }

    /** The line of each {@code plugins[i].name} key, by entry index. */
    static Map<Integer, Integer> pluginNameLines(byte[] manifest) throws IOException {
        Map<Integer, Integer> lines = new HashMap<>();
        try (JsonParser parser = MAPPER.getFactory().createParser(manifest)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != JsonToken.FIELD_NAME || !"name".equals(parser.currentName())) {
                    continue;
                }
                JsonStreamContext array = parser.getParsingContext().getParent();
                JsonStreamContext top = array == null ? null : array.getParent();
                if (array != null
                        && array.inArray()
                        && top != null
                        && top.inObject()
                        && "plugins".equals(top.getCurrentName())
                        && top.getParent() != null
                        && top.getParent().inRoot()) {
                    lines.putIfAbsent(
                            array.getCurrentIndex(),
                            parser.currentTokenLocation().getLineNr());
                }
            }
        }
        return lines;
    }

    private static byte[] fileBytes(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
            return tree == null
                    ? null
                    : repo.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes();
        }
    }

    /**
     * The variables of one evaluation: {@code snapshot}, {@code files}, {@code plugins}, {@code skills}.
     *
     * <p>The closure (GW_INGEST_0030.6) is folded into the same variables rather than added as a fifth: a
     * rule about external content is a rule about plugins, so each plugin carries its
     * {@code origin}, {@code upstreamUrl} and {@code resolvedSha}, and the snapshot carries the
     * count. New signal for the existing gate, not a new gate.
     */
    @Requirements({"GW_APPROVAL_0007", "GW_INGEST_0030.6"})
    public Map<String, Object> build(Snapshot snapshot, Marketplace marketplace) {
        Map<String, SnapshotClosure.Member> closure = new HashMap<>();
        closures.findBySnapshot(snapshot.id()).ifPresent(recorded -> {
            for (SnapshotClosure.Member member : recorded.members()) {
                closure.put(member.graftPath(), member);
            }
        });
        Map<String, Object> snapshotFacts = new HashMap<>();
        snapshotFacts.put("id", snapshot.id());
        snapshotFacts.put("sha", snapshot.sha());
        snapshotFacts.put("upstreamSha", snapshot.upstreamSha());
        snapshotFacts.put("externalSources", closure.size());
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
                SnapshotClosure.Member member = closure.get(graftPath(plugin.source()));
                pluginFacts.put("origin", member == null ? "local" : "external");
                pluginFacts.put("upstreamUrl", member == null ? "" : member.cloneUrl());
                pluginFacts.put("resolvedSha", member == null ? "" : member.resolvedSha());
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

    /** The manifest writes a grafted source as {@code ./_plugins/<name>}; the closure keys on the path. */
    private static String graftPath(String source) {
        if (source == null) {
            return "";
        }
        return source.startsWith("./") ? source.substring(2) : source;
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
