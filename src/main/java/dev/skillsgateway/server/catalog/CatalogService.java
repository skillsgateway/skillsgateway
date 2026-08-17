package dev.skillsgateway.server.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The global virtual catalog (GW_0061–GW_0063): one synthesized repository, served by the ordinary
 * facade under the reserved name, whose tree vendors the currently served snapshot of every
 * marketplace under a subdirectory and merges their manifests into one.
 *
 * <p>Strictly derived content: the rebuild reads only {@code refs/heads/main} of each published
 * repository — the same ref the facade serves — so the catalog cannot disagree with the facade and
 * nothing held, rejected, or revoked can enter it. Each catalog commit is parentless, so a
 * retracted constituent is unreachable from every advertised catalog ref the moment the next
 * rebuild lands.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final String MANIFEST_DIR = ".claude-plugin";
    private static final String MANIFEST_FILE = "marketplace.json";
    private static final String MANIFEST_PATH = MANIFEST_DIR + "/" + MANIFEST_FILE;
    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String INTERNAL_REF_PREFIX = "refs/catalog/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitStorage storage;
    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties.Catalog properties;
    private final Object rebuildLock = new Object();

    public CatalogService(
            GitStorage storage, MarketplaceRepository marketplaceRepository, SkillsGatewayProperties properties) {
        this.storage = storage;
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties.catalog();
    }

    @Schema(description = "One marketplace inside the served catalog revision")
    public record Constituent(
            @Schema(description = "Marketplace name; also its subdirectory in the catalog")
            String marketplace,

            @Schema(description = "Upstream commit SHA of its served snapshot")
            String sha) {}

    @Schema(description = "The catalog revision the facade is serving")
    public record CatalogInfo(
            @Schema(description = "Catalog commit SHA") String sha,

            @Schema(description = "When this revision was generated")
            Instant generatedAt,

            @Schema(description = "Served marketplaces vendored into this revision")
            List<Constituent> constituents) {}

    public boolean enabled() {
        return properties.enabled();
    }

    public String name() {
        return properties.name();
    }

    /**
     * Rebuild for the two publication-changing paths (GW_0062): never lets a catalog problem fail
     * the approval or revocation that triggered it.
     */
    @Requirements({"GW_0062"})
    public void rebuildQuietly() {
        if (!properties.enabled()) {
            return;
        }
        try {
            rebuild();
        } catch (IOException | GitAPIException | RuntimeException e) {
            log.error("virtual catalog rebuild failed; POST /api/catalog/rebuild repairs on demand", e);
        }
    }

    /**
     * Synthesizes one parentless catalog commit from what every marketplace is serving right now
     * and force-updates the catalog's {@code main} to it (GW_0061). Serialized: concurrent
     * approvals rebuild one after the other, and last-writer-wins is correct because every rebuild
     * reads the current served state in full.
     */
    @Requirements({"GW_0061", "GW_0062"})
    public CatalogInfo rebuild() throws IOException, GitAPIException {
        synchronized (rebuildLock) {
            try (Repository catalog = storage.published(properties.name())) {
                List<Constituent> constituents = new ArrayList<>();
                Map<String, ObjectId> subtrees = new LinkedHashMap<>();
                ObjectNode manifest = MAPPER.createObjectNode();
                manifest.put("name", properties.name());
                manifest.set("owner", MAPPER.createObjectNode().put("name", "skills-gateway"));
                ArrayNode plugins = manifest.putArray("plugins");
                Map<String, String> pluginOwners = new LinkedHashMap<>();

                List<Marketplace> marketplaces = marketplaceRepository.list().stream()
                        .sorted(Comparator.comparing(Marketplace::name))
                        .toList();
                for (Marketplace marketplace : marketplaces) {
                    Optional<Repository> serving = storage.publishedIfServing(marketplace.name());
                    if (serving.isEmpty()) {
                        continue;
                    }
                    try (Repository published = serving.get()) {
                        ObjectId tip = published.resolve(MAIN);
                        if (tip == null) {
                            continue;
                        }
                        vendor(catalog, published, marketplace.name(), tip, subtrees, plugins, pluginOwners);
                        constituents.add(new Constituent(marketplace.name(), tip.name()));
                    }
                }

                ObjectId commit = commitCatalog(catalog, subtrees, manifest, constituents);
                pruneInternalRefs(catalog);
                return new CatalogInfo(commit.name(), Instant.now(), constituents);
            }
        }
    }

    /** The revision the facade is serving, parsed back out of the catalog commit (GW_0063). */
    @Requirements({"GW_0063"})
    public Optional<CatalogInfo> served() throws IOException {
        Optional<Repository> serving = storage.publishedIfServing(properties.name());
        if (serving.isEmpty()) {
            return Optional.empty();
        }
        try (Repository catalog = serving.get();
                RevWalk walk = new RevWalk(catalog)) {
            ObjectId tip = catalog.resolve(MAIN);
            if (tip == null) {
                return Optional.empty();
            }
            RevCommit commit = walk.parseCommit(tip);
            List<Constituent> constituents = commit.getFullMessage()
                    .lines()
                    .map(String::strip)
                    .filter(line -> line.matches("^[a-z0-9][a-z0-9_-]* [0-9a-f]{40}$"))
                    .map(line -> {
                        String[] parts = line.split(" ");
                        return new Constituent(parts[0], parts[1]);
                    })
                    .toList();
            return Optional.of(
                    new CatalogInfo(tip.name(), Instant.ofEpochSecond(commit.getCommitTime()), constituents));
        }
    }

    /**
     * Pulls one served tip into the catalog repository (local fetch — pure object reuse) and
     * folds its manifest into the merged one, sources rewritten into the vendored subtree.
     */
    private void vendor(
            Repository catalog,
            Repository published,
            String name,
            ObjectId tip,
            Map<String, ObjectId> subtrees,
            ArrayNode plugins,
            Map<String, String> pluginOwners)
            throws IOException, GitAPIException {
        try (Git git = new Git(catalog)) {
            git.fetch()
                    .setRemote(published.getDirectory().getAbsolutePath())
                    .setRefSpecs(new RefSpec("+" + MAIN + ":" + INTERNAL_REF_PREFIX + name))
                    .call();
        }
        try (RevWalk walk = new RevWalk(catalog)) {
            RevCommit commit = walk.parseCommit(tip);
            subtrees.put(name, commit.getTree().getId());
            try (TreeWalk tree = TreeWalk.forPath(catalog, MANIFEST_PATH, commit.getTree())) {
                if (tree == null) {
                    return;
                }
                JsonNode parsed =
                        MAPPER.readTree(catalog.open(tree.getObjectId(0)).getBytes());
                for (JsonNode plugin : parsed.path("plugins")) {
                    mergePlugin(name, plugin, plugins, pluginOwners);
                }
            }
        }
    }

    /**
     * Namespaced merge (GW_0061): names prefixed with the marketplace, sources rewritten under its
     * subdirectory. A prefix collision keeps the first in marketplace-name order and logs the rest
     * — a documented limit of the naming scheme, not a silent drop.
     */
    private static void mergePlugin(
            String marketplace, JsonNode plugin, ArrayNode plugins, Map<String, String> pluginOwners) {
        String prefixed = marketplace + "-" + plugin.path("name").asText("unnamed");
        String owner = pluginOwners.putIfAbsent(prefixed, marketplace);
        if (owner != null) {
            log.warn(
                    "catalog plugin name collision: '{}' from marketplace '{}' already taken by '{}'; keeping the first",
                    prefixed,
                    marketplace,
                    owner);
            return;
        }
        ObjectNode merged = plugin.deepCopy();
        merged.put("name", prefixed);
        String source = plugin.path("source").asText("");
        String relative = source.startsWith("./") ? source.substring(2) : source;
        merged.put("source", "./" + marketplace + "/" + relative);
        plugins.add(merged);
    }

    /** One parentless commit (GW_0062): history depth 1, so old compositions are unreachable. */
    private ObjectId commitCatalog(
            Repository catalog, Map<String, ObjectId> subtrees, ObjectNode manifest, List<Constituent> constituents)
            throws IOException {
        try (ObjectInserter inserter = catalog.newObjectInserter()) {
            byte[] manifestBytes = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest)
                    .getBytes(StandardCharsets.UTF_8);
            ObjectId manifestBlob = inserter.insert(Constants.OBJ_BLOB, manifestBytes);
            TreeFormatter manifestDir = new TreeFormatter();
            manifestDir.append(MANIFEST_FILE, FileMode.REGULAR_FILE, manifestBlob);
            ObjectId manifestTree = inserter.insert(manifestDir);

            // Git trees must be byte-sorted; all entries here are trees, so a plain name sort is
            // canonical. ".claude-plugin" sorts before every marketplace name (lowercase alnum).
            TreeFormatter root = new TreeFormatter();
            Map<String, ObjectId> entries = new LinkedHashMap<>();
            entries.put(MANIFEST_DIR, manifestTree);
            entries.putAll(subtrees);
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> root.append(entry.getKey(), FileMode.TREE, entry.getValue()));
            ObjectId rootTree = inserter.insert(root);

            StringBuilder message = new StringBuilder("virtual catalog\n\n");
            for (Constituent constituent : constituents) {
                message.append(constituent.marketplace())
                        .append(' ')
                        .append(constituent.sha())
                        .append('\n');
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(rootTree);
            PersonIdent gateway = new PersonIdent("skills-gateway", "gateway@localhost");
            commit.setAuthor(gateway);
            commit.setCommitter(gateway);
            commit.setMessage(message.toString());
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate main = catalog.updateRef(MAIN);
            main.setNewObjectId(commitId);
            main.forceUpdate();
            return commitId;
        }
    }

    /** The fetch refs are scaffolding; only main (and nothing else) stays advertised. */
    private static void pruneInternalRefs(Repository catalog) throws IOException {
        for (Ref ref : catalog.getRefDatabase().getRefsByPrefix(INTERNAL_REF_PREFIX)) {
            RefUpdate update = catalog.updateRef(ref.getName());
            update.setForceUpdate(true);
            update.delete();
        }
    }
}
