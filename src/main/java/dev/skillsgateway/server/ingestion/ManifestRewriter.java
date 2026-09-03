package dev.skillsgateway.server.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

/**
 * Synthesises the commit the gateway serves for a manifest with resolved external sources
 * (GW_0156): the upstream tree, each resolved source grafted under {@code _plugins/<name>}, and a
 * manifest in which every plugin source is a path inside that same commit.
 *
 * <p>Three properties are what the rest of the system rests on.
 *
 * <ul>
 *   <li><b>The composite is the snapshot.</b> Vetting opens {@code snapshot.sha()}, approval copies
 *       it, the facade advertises it, retention anchors on it — so making the served commit the
 *       identity means none of them learns that external content exists (ADR 0011 §2).
 *   <li><b>The upstream commit is the parent.</b> The manifest as upstream declared it stays
 *       byte-exact and reachable from the served SHA, so "what did the gateway change" is
 *       {@code git diff <parent> <served>} rather than the gateway's own word for it.
 *   <li><b>The same inputs give the same commit.</b> Fixed identity, epoch-zero timestamp, and the
 *       transformer version stamped into the message so that determinism survives a change to this
 *       class: a rewriter that altered its output without altering the SHA would serve new content
 *       under a commit that was already approved.
 * </ul>
 *
 * <p>Everywhere a manifest-supplied name would decide where content is written, this fails closed —
 * and then checks its own work: the rewritten manifest is put back through {@link ManifestPolicy}'s
 * local-only gate, so GW_0152 holds because the commit could not otherwise be used, rather than
 * because this class is believed to be correct.
 */
@Component
public class ManifestRewriter {

    /**
     * The identity of this transformation, stamped into every composite commit. Bumping it is a
     * deliberate act: every composite changes SHA and goes back through vetting and approval, which
     * is the correct behaviour for content the gateway now assembles differently.
     */
    public static final String TRANSFORMER_VERSION = "1";

    /** Where resolved external content lives in the composite. Reserved: a collision is refused. */
    static final String GRAFT_DIR = "_plugins";

    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The facade's marketplace-name shape: one lowercase path segment, and nothing else. */
    private static final Pattern PLUGIN_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private static final PersonIdent GATEWAY =
            new PersonIdent("skills-gateway", "gateway@localhost", Instant.EPOCH, ZoneOffset.UTC);

    private final ManifestPolicy policy;
    private final String transformerVersion;

    @org.springframework.beans.factory.annotation.Autowired
    public ManifestRewriter(ManifestPolicy policy) {
        this(policy, TRANSFORMER_VERSION);
    }

    private ManifestRewriter(ManifestPolicy policy, String transformerVersion) {
        this.policy = policy;
        this.transformerVersion = transformerVersion;
    }

    /**
     * The same rewriter stamping a different transformer version. Exists so that the version's
     * effect on the composite's identity is a testable property rather than a claim.
     */
    ManifestRewriter withTransformerVersion(String version) {
        return new ManifestRewriter(policy, version);
    }

    /** One external plugin's resolved content, ready to graft. */
    public record Graft(String pluginName, String cloneUrl, ObjectId resolvedSha, ObjectId tree) {}

    /** The synthesised commit, or the reason there is none. Exactly one is non-null. */
    public record Rewrite(ObjectId commit, String violation) {

        static Rewrite refused(String violation) {
            return new Rewrite(null, violation);
        }
    }

    @Requirements({"GW_0152", "GW_0156"})
    public Rewrite rewrite(Repository repository, RevCommit upstream, List<Graft> grafts) throws IOException {
        String hazard = graftHazard(repository, upstream, grafts);
        if (hazard != null) {
            return Rewrite.refused(hazard);
        }
        byte[] upstreamManifest = manifestBytes(repository, upstream);
        if (upstreamManifest == null) {
            return Rewrite.refused("missing " + MANIFEST_PATH);
        }
        ManifestPolicy.Evaluation evaluation = policy.evaluate(upstreamManifest);
        if (evaluation.rejected()) {
            return Rewrite.refused(evaluation.violation());
        }
        String undeclared = requireEveryGraftIsDeclared(evaluation, grafts);
        if (undeclared != null) {
            return Rewrite.refused(undeclared);
        }
        byte[] rewritten = rewriteManifest(evaluation, grafts);
        // The post-condition, and the whole reason GW_0152 is structural: the manifest this class
        // just produced goes through the same gate that rejected the original. A rewrite that
        // missed a source cannot become a commit, whatever this class believes it did.
        String stillExternal = policy.validate(rewritten);
        if (stillExternal != null) {
            return Rewrite.refused("the rewritten " + MANIFEST_PATH + " still declares a source outside the gateway: "
                    + stillExternal);
        }
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId rootTree = compose(repository, inserter, upstream, grafts, rewritten);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(rootTree);
            commit.setParentId(upstream);
            commit.setAuthor(GATEWAY);
            commit.setCommitter(GATEWAY);
            commit.setMessage(message(upstream, grafts));
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return new Rewrite(id, null);
        }
    }

    /** Everything about the grafts that would decide where content is written. */
    private String graftHazard(Repository repository, RevCommit upstream, List<Graft> grafts) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Graft graft : grafts) {
            String name = graft.pluginName();
            if (name == null || !PLUGIN_NAME.matcher(name).matches()) {
                return "plugin name '%s' is not a single lowercase path segment, so its content cannot be grafted"
                        .formatted(name);
            }
            if (!seen.add(name)) {
                return "two external plugins are both named '%s', so their content would collide".formatted(name);
            }
        }
        if (!grafts.isEmpty() && rootContains(repository, upstream, GRAFT_DIR)) {
            return "the marketplace repository already contains a top-level '%s', which the gateway reserves for"
                            .formatted(GRAFT_DIR)
                    + " resolved external plugin content";
        }
        return null;
    }

    /**
     * A graft naming a plugin the manifest does not declare as an external source is a caller
     * defect the output cannot show: nothing would point at the grafted content, so the composite
     * would look correct while carrying a tree nobody asked for.
     *
     * <p>The opposite direction — an admitted source with no graft — is deliberately <em>not</em>
     * checked here. It is caught by the post-condition instead, because a source that was not
     * resolved is not rewritten and the composite manifest therefore still declares it externally.
     * One enforcement point over the actual output beats two over descriptions of it, and mutation
     * testing is what made the difference visible: with this direction checked here as well,
     * dropping the post-condition changed nothing any test could see.
     */
    private static String requireEveryGraftIsDeclared(ManifestPolicy.Evaluation evaluation, List<Graft> grafts) {
        Set<String> admitted = new HashSet<>();
        for (ManifestPolicy.Admitted source : evaluation.admitted()) {
            admitted.add(source.pluginName());
        }
        for (Graft graft : grafts) {
            if (!admitted.contains(graft.pluginName())) {
                return "there is resolved content for plugin '%s', which the manifest does not declare as an"
                                .formatted(graft.pluginName())
                        + " external source";
            }
        }
        return null;
    }

    /**
     * The manifest with each <em>resolved</em> source replaced by the path its content was grafted
     * to. Rewriting is positional: two plugins may share a name, and a rewrite that matched on the
     * name would have to guess which entry it meant.
     *
     * <p>A source with no graft is left exactly as the manifest declared it, which is what lets the
     * post-condition see it: an unresolved external source stays external, and the local-only gate
     * refuses the composite.
     */
    private byte[] rewriteManifest(ManifestPolicy.Evaluation evaluation, List<Graft> grafts) throws IOException {
        Set<String> grafted = new HashSet<>();
        for (Graft graft : grafts) {
            grafted.add(graft.pluginName());
        }
        ObjectNode manifest = evaluation.manifest().deepCopy();
        JsonNode plugins = manifest.get("plugins");
        for (ManifestPolicy.Admitted source : evaluation.admitted()) {
            if (!grafted.contains(source.pluginName())) {
                continue;
            }
            ObjectNode plugin = (ObjectNode) plugins.get(source.index());
            plugin.put("source", "./" + GRAFT_DIR + "/" + source.pluginName());
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
    }

    /**
     * The composite root tree, built through an in-core {@link DirCache} rather than hand-formatted
     * trees: canonical entry ordering is then JGit's problem, and a tree whose entries are a byte
     * out of order is a corrupt commit that only some clients notice.
     */
    private static ObjectId compose(
            Repository repository,
            ObjectInserter inserter,
            RevCommit upstream,
            List<Graft> grafts,
            byte[] rewrittenManifest)
            throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, upstream.getTree());
            for (Graft graft : grafts) {
                builder.addTree(
                        (GRAFT_DIR + "/" + graft.pluginName()).getBytes(StandardCharsets.UTF_8),
                        DirCacheEntry.STAGE_0,
                        reader,
                        graft.tree());
            }
            builder.finish();

            ObjectId manifestBlob = inserter.insert(Constants.OBJ_BLOB, rewrittenManifest);
            DirCacheEditor editor = cache.editor();
            editor.add(new DirCacheEditor.PathEdit(MANIFEST_PATH) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(manifestBlob);
                }
            });
            editor.finish();
            return cache.writeTree(inserter);
        }
    }

    /**
     * The provenance an auditor and a reviewer read: what was ingested, what each source was and
     * what it resolved to, and which transformation produced this. It is also part of the commit,
     * so it is part of the identity — which is what makes the transformer version an input.
     */
    private String message(RevCommit upstream, List<Graft> grafts) {
        StringBuilder message = new StringBuilder("gateway composite snapshot\n\n");
        message.append("Upstream-Commit: ").append(upstream.name()).append('\n');
        for (Graft graft : grafts) {
            message.append("Resolved-Source: ")
                    .append(graft.pluginName())
                    .append(' ')
                    .append(graft.cloneUrl())
                    .append(' ')
                    .append(graft.resolvedSha().name())
                    .append('\n');
        }
        message.append("Transformer-Version: ").append(transformerVersion).append('\n');
        return message.toString();
    }

    private static boolean rootContains(Repository repository, RevCommit commit, String name) throws IOException {
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(false);
            while (walk.next()) {
                if (name.equals(walk.getNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static byte[] manifestBytes(Repository repository, RevCommit commit) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, MANIFEST_PATH, commit.getTree())) {
            return walk == null ? null : repository.open(walk.getObjectId(0)).getBytes();
        }
    }
}
