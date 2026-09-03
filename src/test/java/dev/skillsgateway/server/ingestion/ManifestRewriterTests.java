package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.ingestion.ManifestRewriter.Graft;
import dev.skillsgateway.server.ingestion.ManifestRewriter.Rewrite;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The composite snapshot (GW_0156) built against an in-memory repository, so the whole
 * transformation is exercised without a database, a Spring context or a network.
 *
 * <p>The claims worth proving here are all structural. The rewritten manifest must point only
 * inside the commit; the upstream commit must survive as the composite's parent with its manifest
 * byte-exact, because that is what turns "what did the gateway change" into a diff someone else can
 * run; the same inputs must give the same SHA, because the ingestion dedupe is built on it; and
 * every way manifest-supplied names could decide where content is written must fail closed.
 *
 * <p>The last test is the GW_0152 post-condition: the composite is put through the same local-only
 * gate that rejected the original, so a rewrite that missed a source cannot be served.
 */
class ManifestRewriterTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_PATH = ".claude-plugin/marketplace.json";

    private static final String UPSTREAM_MANIFEST = """
            {
              "name": "test-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "local"},
                {"name": "tools", "source": {"source": "github", "repo": "acme/tools"}, "description": "external"}
              ]
            }
            """;

    private Repository repository;
    private ManifestRewriter rewriter;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository(new DfsRepositoryDescription("rewriter"));
        rewriter = new ManifestRewriter(new ManifestPolicy(
                new ExternalSourceAdmission(true, Set.of("github"), Set.of(), Set.of("https"), 20, "https://x.test")));
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_rewritten_manifest_points_only_inside_the_commit() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of("plugins/hello/skills/hi/SKILL.md", "# hi\n"));
        ObjectId pluginTree = tree(Map.of("skills/tool/SKILL.md", "# tool\n"));

        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)));

        assertThat(rewrite.violation()).isNull();
        JsonNode manifest = MAPPER.readTree(fileAt(rewrite.commit(), MANIFEST_PATH));
        assertThat(manifest.get("plugins").get(1).get("source").asText()).isEqualTo("./_plugins/tools");
        // Everything else passes through: the local source, the descriptions, the owner block.
        assertThat(manifest.get("plugins").get(0).get("source").asText()).isEqualTo("./plugins/hello");
        assertThat(manifest.get("plugins").get(1).get("description").asText()).isEqualTo("external");
        assertThat(manifest.get("owner").get("name").asText()).isEqualTo("Test");
        assertThat(manifest.get("name").asText()).isEqualTo("test-marketplace");
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_grafted_content_is_present_under_the_reserved_directory() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of("plugins/hello/skills/hi/SKILL.md", "# hi\n"));
        ObjectId pluginTree = tree(Map.of("skills/tool/SKILL.md", "# tool\n", "README.md", "tools\n"));

        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)));

        assertThat(fileAt(rewrite.commit(), "_plugins/tools/skills/tool/SKILL.md"))
                .isEqualTo("# tool\n");
        assertThat(fileAt(rewrite.commit(), "_plugins/tools/README.md")).isEqualTo("tools\n");
        // The upstream content is still there, unchanged.
        assertThat(fileAt(rewrite.commit(), "plugins/hello/skills/hi/SKILL.md")).isEqualTo("# hi\n");
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_upstream_commit_is_the_parent_and_its_manifest_stays_byte_exact() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("tools", tree(Map.of("a", "b")))));

        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit composite = walk.parseCommit(rewrite.commit());
            assertThat(composite.getParentCount()).isEqualTo(1);
            assertThat(composite.getParent(0).name()).isEqualTo(upstream.name());
        }
        // An auditor diffs the parent against the served commit; that only works if the parent's
        // manifest is what upstream actually declared, to the byte.
        assertThat(fileAt(upstream, MANIFEST_PATH)).isEqualTo(UPSTREAM_MANIFEST);
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_commit_records_the_upstream_commit_each_source_and_the_transformer() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        ObjectId resolved = ObjectId.fromString("1234567890123456789012345678901234567890");

        Rewrite rewrite = rewriter.rewrite(
                repository,
                upstream,
                List.of(new Graft("tools", "https://x.test/acme/tools", resolved, tree(Map.of("a", "b")))));

        try (RevWalk walk = new RevWalk(repository)) {
            String message = walk.parseCommit(rewrite.commit()).getFullMessage();
            assertThat(message)
                    .contains(upstream.name())
                    .contains("https://x.test/acme/tools")
                    .contains(resolved.name())
                    .contains("tools")
                    .contains(ManifestRewriter.TRANSFORMER_VERSION);
        }
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_same_inputs_produce_the_same_commit() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        ObjectId pluginTree = tree(Map.of("skills/tool/SKILL.md", "# tool\n"));

        ObjectId first = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)))
                .commit();
        ObjectId again = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)))
                .commit();

        assertThat(again).isEqualTo(first);
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_different_resolved_commit_produces_a_different_composite() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        ObjectId pluginTree = tree(Map.of("skills/tool/SKILL.md", "# tool\n"));
        ObjectId moved = tree(Map.of("skills/tool/SKILL.md", "# tool, moved on\n"));

        ObjectId first = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)))
                .commit();
        ObjectId second = rewriter.rewrite(repository, upstream, List.of(graft("tools", moved)))
                .commit();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_transformer_version_is_an_input_to_the_composite_identity() throws Exception {
        // Determinism is only a durable claim if the transformation is itself an input: a rewriter
        // that changed its output without changing the SHA would serve new content under a commit
        // that was already approved.
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        ObjectId pluginTree = tree(Map.of("a", "b"));

        ObjectId shipped = rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree)))
                .commit();
        ObjectId bumped = rewriter.withTransformerVersion(ManifestRewriter.TRANSFORMER_VERSION + "-next")
                .rewrite(repository, upstream, List.of(graft("tools", pluginTree)))
                .commit();

        assertThat(bumped).isNotEqualTo(shipped);
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_reserved_directory_already_present_upstream_is_refused_with_no_commit() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of("_plugins/tools/SKILL.md", "# planted\n"));

        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("tools", tree(Map.of("a", "b")))));

        assertThat(rewrite.violation()).isNotNull().contains("_plugins");
        assertThat(rewrite.commit()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_plugin_name_that_is_not_a_single_lowercase_path_segment_is_refused_with_no_commit() throws Exception {
        ObjectId pluginTree = tree(Map.of("a", "b"));

        for (String name : new String[] {
            "../evil", "a/b", "_plugins", "Tools", "", ".", "..", "tools ", "tools/", "-tools", "tools.d"
        }) {
            // The manifest declares the plugin under the same bad name, so the name pattern is the
            // only thing that can refuse it. Declaring it under some other name would let the
            // graft-is-declared check answer first and the pattern would go untested — which is
            // exactly what a mutant that widened the pattern proved about the earlier version of
            // this test.
            RevCommit upstream = upstream(manifestDeclaringExternal(name), Map.of());

            Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft(name, pluginTree)));

            assertThat(rewrite.violation()).as("name '%s'", name).isNotNull().contains("lowercase path segment");
            assertThat(rewrite.commit()).as("name '%s'", name).isNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void two_external_plugins_sharing_a_name_are_refused_with_no_commit() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());
        ObjectId pluginTree = tree(Map.of("a", "b"));

        Rewrite rewrite =
                rewriter.rewrite(repository, upstream, List.of(graft("tools", pluginTree), graft("tools", pluginTree)));

        assertThat(rewrite.violation()).isNotNull().contains("tools");
        assertThat(rewrite.commit()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_graft_for_a_plugin_the_manifest_does_not_declare_is_refused_with_no_commit() throws Exception {
        RevCommit upstream = upstream(UPSTREAM_MANIFEST, Map.of());

        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("absent", tree(Map.of("a", "b")))));

        assertThat(rewrite.violation()).isNotNull();
        assertThat(rewrite.commit()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0152", "SVC_GW_0156"})
    void the_composite_manifest_is_put_back_through_the_local_only_gate() throws Exception {
        // The post-condition, and the reason it is not merely a comment: a manifest declaring two
        // external sources with a graft for only one of them must not produce a servable commit.
        String twoExternal = """
                {
                  "name": "m",
                  "plugins": [
                    {"name": "tools", "source": {"source": "github", "repo": "acme/tools"}},
                    {"name": "extra", "source": {"source": "github", "repo": "acme/extra"}}
                  ]
                }
                """;
        RevCommit upstream = upstream(twoExternal, Map.of());

        Rewrite rewrite = rewriter.rewrite(repository, upstream, List.of(graft("tools", tree(Map.of("a", "b")))));

        assertThat(rewrite.violation()).isNotNull();
        assertThat(rewrite.commit()).isNull();
    }

    /** A manifest whose single plugin is external and carries exactly this name. */
    private static String manifestDeclaringExternal(String pluginName) {
        return "{\"name\":\"m\",\"plugins\":[{\"name\":%s,\"source\":{\"source\":\"github\",\"repo\":\"a/b\"}}]}"
                .formatted(MAPPER.getNodeFactory().textNode(pluginName).toString());
    }

    private static Graft graft(String pluginName, ObjectId tree) {
        return new Graft(
                pluginName,
                "https://x.test/acme/" + pluginName,
                ObjectId.fromString("0123456789012345678901234567890123456789"),
                tree);
    }

    /** An upstream commit carrying the manifest and any extra files, built in memory. */
    private RevCommit upstream(String manifest, Map<String, String> extraFiles) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put(MANIFEST_PATH, manifest);
        files.putAll(extraFiles);
        ObjectId rootTree = tree(files);
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(rootTree);
            PersonIdent ident = new PersonIdent("Upstream", "upstream@example.com");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("upstream content");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            try (RevWalk walk = new RevWalk(repository)) {
                return walk.parseCommit(id);
            }
        }
    }

    /** A tree from a path-to-content map, via the same in-core DirCache the rewriter uses. */
    private ObjectId tree(Map<String, String> files) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Map.Entry<String, String> file : files.entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            ObjectId id = cache.writeTree(inserter);
            inserter.flush();
            return id;
        }
    }

    private String fileAt(ObjectId commitId, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                assertThat(tree)
                        .as("path %s exists in %s", path, commitId.name())
                        .isNotNull();
                return new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
