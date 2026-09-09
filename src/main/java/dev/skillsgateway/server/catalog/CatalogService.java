package dev.skillsgateway.server.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.ActorType;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.RefTransitions;
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
 * The global virtual catalog (GW_FACADE_0003–GW_FACADE_0005): one synthesized repository, served by the ordinary
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
    private static final String COLLISION_LINE = "collision ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The gateway's own name on a ledger entry it writes about its own derived catalog. */
    public static final String ACTOR = "catalog-builder";

    private final GitStorage storage;
    private final MarketplaceRepository marketplaceRepository;
    private final SkillsGatewayProperties.Catalog properties;
    private final AdminAuditLogger auditLogger;
    private final GatewayMetrics metrics;
    private final Object rebuildLock = new Object();

    public CatalogService(
            GitStorage storage,
            MarketplaceRepository marketplaceRepository,
            SkillsGatewayProperties properties,
            AdminAuditLogger auditLogger,
            GatewayMetrics metrics) {
        this.storage = storage;
        this.marketplaceRepository = marketplaceRepository;
        this.properties = properties.catalog();
        this.auditLogger = auditLogger;
        this.metrics = metrics;
    }

    /** One name claimed by one marketplace, already rewritten, held until every claim is in. */
    private record Claim(String marketplace, ObjectNode entry) {}

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
            List<Constituent> constituents,

            @Schema(description = "Names claimed by more than one marketplace; none of them is published")
            List<Collision> collisions) {}

    @Schema(description = "A catalog plugin name more than one marketplace claims")
    public record Collision(
            @Schema(description = "The contested name, which this revision does not publish")
            String name,

            @Schema(description = "Every marketplace that claimed it, in name order")
            List<String> marketplaces) {}

    public boolean enabled() {
        return properties.enabled();
    }

    public String name() {
        return properties.name();
    }

    /**
     * Rebuild for the two publication-changing paths (GW_FACADE_0004): never lets a catalog problem fail
     * the approval or revocation that triggered it.
     */
    @Requirements({"GW_FACADE_0004"})
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
     * and force-updates the catalog's {@code main} to it (GW_FACADE_0003). Serialized: concurrent
     * approvals rebuild one after the other, and last-writer-wins is correct because every rebuild
     * reads the current served state in full.
     */
    @Requirements({"GW_FACADE_0003", "GW_FACADE_0004"})
    public CatalogInfo rebuild() throws IOException, GitAPIException {
        synchronized (rebuildLock) {
            try (Repository catalog = storage.published(properties.name())) {
                List<Constituent> constituents = new ArrayList<>();
                Map<String, ObjectId> subtrees = new LinkedHashMap<>();
                ObjectNode manifest = MAPPER.createObjectNode();
                manifest.put("name", properties.name());
                manifest.set("owner", MAPPER.createObjectNode().put("name", "skills-gateway"));
                ArrayNode plugins = manifest.putArray("plugins");
                // Every claim on every name, gathered before any of them is published: a first-wins
                // rule cannot retract the winner when the second claimant arrives, and retracting is
                // the whole of the fix (GW_FACADE_0029).
                Map<String, List<Claim>> claims = new LinkedHashMap<>();

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
                        vendor(catalog, published, marketplace.name(), tip, subtrees, claims);
                        constituents.add(new Constituent(marketplace.name(), tip.name()));
                    }
                }

                List<Collision> collisions = publishUncontested(claims, plugins);
                ObjectId commit = commitCatalog(catalog, subtrees, manifest, constituents, collisions);
                pruneInternalRefs(catalog);
                announce(collisions);
                return new CatalogInfo(commit.name(), Instant.now(), constituents, collisions);
            }
        }
    }

    /** The revision the facade is serving, parsed back out of the catalog commit (GW_FACADE_0005). */
    @Requirements({"GW_FACADE_0005"})
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
            List<Collision> collisions = commit.getFullMessage()
                    .lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith(COLLISION_LINE))
                    .map(line -> line.substring(COLLISION_LINE.length()).split(" ", 2))
                    .filter(parts -> parts.length == 2)
                    .map(parts -> new Collision(parts[0], List.of(parts[1].split(","))))
                    .toList();
            return Optional.of(new CatalogInfo(
                    tip.name(), Instant.ofEpochSecond(commit.getCommitTime()), constituents, collisions));
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
            Map<String, List<Claim>> claims)
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
                    mergePlugin(name, plugin, claims);
                }
            }
        }
    }

    /**
     * Namespaced merge (GW_FACADE_0003): names prefixed with the marketplace, sources rewritten under
     * its subdirectory. Records the claim rather than publishing it — {@link #publishUncontested}
     * decides, once every marketplace has been read.
     */
    private static void mergePlugin(String marketplace, JsonNode plugin, Map<String, List<Claim>> claims) {
        String prefixed = marketplace + "-" + plugin.path("name").asText("unnamed");
        ObjectNode merged = plugin.deepCopy();
        merged.put("name", prefixed);
        String source = plugin.path("source").asText("");
        String relative = source.startsWith("./") ? source.substring(2) : source;
        merged.put("source", "./" + marketplace + "/" + relative);
        claims.computeIfAbsent(prefixed, name -> new ArrayList<>()).add(new Claim(marketplace, merged));
    }

    /**
     * Publishes exactly the names one marketplace claims, and withholds every contested one from
     * both claimants (GW_FACADE_0029).
     *
     * <p>The prefix map is not injective — marketplace {@code a} with plugin {@code b-c} and
     * marketplace {@code a-b} with plugin {@code c} both produce {@code a-b-c} — and the rule that
     * stood here published the first in marketplace-name order. That did not merely drop the loser:
     * it served the winner's tree <em>under the name the loser's consumers install</em>, and it was
     * re-decided on every rebuild, so registering a marketplace whose name sorts earlier could
     * change what an existing install name means. Substituting one publisher's content for another's
     * is the one thing a catalog may never do. Omitting a name is legitimate; that is why both go.
     *
     * <p>Insertion order is preserved for the names that survive, so an estate with no collision
     * produces exactly the manifest it did before.
     */
    @Requirements({"GW_FACADE_0029"})
    private static List<Collision> publishUncontested(Map<String, List<Claim>> claims, ArrayNode plugins) {
        List<Collision> collisions = new ArrayList<>();
        for (Map.Entry<String, List<Claim>> claimed : claims.entrySet()) {
            List<Claim> claimants = claimed.getValue();
            if (claimants.size() == 1) {
                plugins.add(claimants.getFirst().entry());
                continue;
            }
            collisions.add(new Collision(
                    claimed.getKey(),
                    claimants.stream()
                            .map(Claim::marketplace)
                            .distinct()
                            .sorted()
                            .toList()));
        }
        return collisions.stream().sorted(Comparator.comparing(Collision::name)).toList();
    }

    /**
     * A withheld name is a plugin its publisher believes is being served, so it is reported rather
     * than only logged: one ledger entry per contested name per rebuild, and a counter. The entry
     * names the gateway itself — the collision is the gateway's finding, not the act of whoever
     * approved the snapshot that triggered the rebuild.
     */
    @Requirements({"GW_FACADE_0029"})
    private void announce(List<Collision> collisions) {
        for (Collision collision : collisions) {
            log.warn(
                    "catalog name collision: '{}' is claimed by {} and is published by none of them",
                    collision.name(),
                    collision.marketplaces());
            metrics.catalogCollision();
            auditLogger.recordAs(
                    ActorType.SYSTEM,
                    ACTOR,
                    properties.name(),
                    "catalog-name-collision",
                    collision.name() + " claimed by " + String.join(", ", collision.marketplaces()));
        }
    }

    /** One parentless commit (GW_FACADE_0004): history depth 1, so old compositions are unreachable. */
    private ObjectId commitCatalog(
            Repository catalog,
            Map<String, ObjectId> subtrees,
            ObjectNode manifest,
            List<Constituent> constituents,
            List<Collision> collisions)
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
            // Collisions ride in the message beside the constituents because the read endpoint
            // reconstructs from the served commit and nothing else: a withheld name that only
            // existed in the rebuild's return value would be invisible to the operator who goes
            // looking for it afterwards (GW_FACADE_0029). The keyword prefix keeps these lines out
            // of the constituent pattern, whose second field is a 40-character SHA.
            for (Collision collision : collisions) {
                message.append(COLLISION_LINE)
                        .append(collision.name())
                        .append(' ')
                        .append(String.join(",", collision.marketplaces()))
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

            // Checked (GW_FACADE_0017): a refused move would have this method return a commit the facade
            // does not serve, and the rebuild endpoint report a sha nobody can fetch.
            RefTransitions.write(catalog, MAIN, commitId);
            return commitId;
        }
    }

    /**
     * The fetch refs are scaffolding; only main (and nothing else) stays in the catalog repository.
     *
     * <p>Checked (GW_FACADE_0017). The facade now advertises an allowlist, so a reference left here is no
     * longer served — but it still holds objects a later revocation should have made unreachable,
     * and a prune that reported success while leaving them is the same class of untruth as a
     * publication that did not happen.
     */
    @Requirements({"GW_FACADE_0017"})
    private static void pruneInternalRefs(Repository catalog) throws IOException {
        for (Ref ref : catalog.getRefDatabase().getRefsByPrefix(INTERNAL_REF_PREFIX)) {
            RefTransitions.delete(catalog, ref.getName());
        }
    }
}
