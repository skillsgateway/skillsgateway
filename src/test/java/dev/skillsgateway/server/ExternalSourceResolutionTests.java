package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.VettingRepository;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * External plugin source resolution end to end (GW_0155, GW_0156, GW_0157, GW_0158, GW_0161),
 * against a real JGit fetch over a real HTTP transport served in this process by
 * {@link GitHttpFixture}.
 *
 * <p>Its own Spring context, because enabling external sources is a deployment decision: the shared
 * context must keep the shipped default, which is what SVC_GW_0003 pins and what
 * {@code IngestionTests} verifies is untouched by this change.
 *
 * <p>{@code allow-private-networks} is on here because the fixture is on loopback — and the
 * metadata-endpoint case below is the check that this does <em>not</em> also unlock the link-local
 * range, which is the whole reason the address policy has two tiers.
 *
 * <p>The suite is deliberately weighted towards refusals. The happy path is one property (a held
 * composite whose manifest points only inside the gateway); everything else is a way the resolver
 * could leave a snapshot half-resolved, contact something it must not, or spend more than it may.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.ingestion.external-sources.enabled=true",
            "skills-gateway.ingestion.external-sources.allowed-types=github",
            "skills-gateway.ingestion.external-sources.allow-private-networks=true",
            // Small enough that the fixture can exceed them, large enough for real fixture repos.
            "skills-gateway.ingestion.external-sources.budgets.max-received-bytes=1MB",
            "skills-gateway.ingestion.external-sources.budgets.max-blob-bytes=64KB",
            "skills-gateway.ingestion.external-sources.budgets.max-redirects=2",
            "skills-gateway.ingestion.external-sources.budgets.deadline=60s"
        })
class ExternalSourceResolutionTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Started in a static initialiser rather than {@code @BeforeAll}: the context reads
     * {@code github-base-url} while it starts, which is before any lifecycle callback runs.
     */
    private static final GitHttpFixture FORGE = startForge();

    private static GitHttpFixture startForge() {
        try {
            return new GitHttpFixture();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the in-process forge", e);
        }
    }

    @DynamicPropertySource
    static void forgeBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("skills-gateway.ingestion.external-sources.github-base-url", FORGE::baseUrl);
    }

    @AfterAll
    static void stopForge() {
        FORGE.close();
    }

    @Autowired
    private GitStorage storage;

    @Autowired
    private SnapshotContentService contentService;

    @Autowired
    private VettingRepository vettingRepository;

    @BeforeEach
    void resetForge() {
        FORGE.reset();
    }

    /** A marketplace manifest declaring one local plugin and one external source. */
    private static String manifestWithExternal(String ownerRepo) {
        return """
                {
                  "name": "test-marketplace",
                  "owner": {"name": "Test"},
                  "plugins": [
                    {"name": "hello", "source": "./plugins/hello", "description": "local"},
                    {"name": "tools", "source": {"source": "github", "repo": "%s"}, "description": "external"}
                  ]
                }
                """.formatted(ownerRepo);
    }

    @Test
    @SVCs({"SVC_GW_0155", "SVC_GW_0156"})
    void an_admitted_source_becomes_a_held_composite_whose_manifest_is_gateway_local() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n\nA tool.\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        String upstreamHead = headSha(upstream);

        Registered registered = registerAndIngest(uniqueName("res"), upstream);
        Snapshot snapshot = registered.snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.HELD);
        assertThat(snapshot.violation()).isNull();
        // The snapshot is the composite, not the upstream commit.
        assertThat(snapshot.sha()).isNotEqualTo(upstreamHead);

        JsonNode manifest = MAPPER.readTree(servedFile(registered.marketplace(), snapshot.sha(), MANIFEST_PATH));
        assertThat(manifest.get("plugins").get(1).get("source").asText()).isEqualTo("./_plugins/tools");
        assertThat(manifest.get("plugins").get(0).get("source").asText()).isEqualTo("./plugins/hello");
        // Nothing a client dereferences leaves the gateway.
        assertThat(manifest.toString()).doesNotContain(FORGE.baseUrl());

        assertThat(servedFile(registered.marketplace(), snapshot.sha(), "_plugins/tools/skills/tool/SKILL.md"))
                .isEqualTo("# Tool\n\nA tool.\n");

        // The composite is what quarantine pins (GW_0137): approval publishes from
        // refs/snapshots/<sha>, so a snapshot row whose commit no reference names would be a
        // reviewable, approvable row publishing from nothing.
        assertThat(pinnedSnapshotRefs(registered.marketplace())).containsExactly("refs/snapshots/" + snapshot.sha());

        // The positive control for every "was never contacted" assertion below: the fixture does
        // record the paths it is asked for, so an empty log there means a request was not made
        // rather than that the log does not work.
        assertThat(FORGE.requestedPaths()).contains("/acme/tools/info/refs");
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_composite_is_parented_on_the_upstream_commit_whose_manifest_stays_byte_exact() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        String declared = manifestWithExternal("acme/tools");
        Path upstream = createUpstream(declared);
        String upstreamHead = headSha(upstream);

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        try (Repository quarantine = storage.quarantine(registered.marketplace().name());
                RevWalk walk = new RevWalk(quarantine)) {
            RevCommit composite =
                    walk.parseCommit(ObjectId.fromString(registered.snapshot().sha()));
            assertThat(composite.getParentCount()).isEqualTo(1);
            assertThat(composite.getParent(0).name()).isEqualTo(upstreamHead);
            // An auditor diffs the parent against the served commit; that only works if the parent
            // still carries exactly what upstream declared.
            assertThat(fileAt(quarantine, composite.getParent(0).name(), MANIFEST_PATH))
                    .isEqualTo(declared);
            assertThat(composite.getFullMessage()).contains(upstreamHead).contains("acme/tools");
        }
    }

    @Test
    @SVCs({"SVC_GW_0155"})
    void the_scaffolding_references_the_fetch_used_are_not_left_behind() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Registered registered =
                registerAndIngest(uniqueName("res"), createUpstream(manifestWithExternal("acme/tools")));

        try (Repository quarantine = storage.quarantine(registered.marketplace().name())) {
            List<Ref> scaffolding = quarantine.getRefDatabase().getRefsByPrefix("refs/plugin-sources/");
            assertThat(scaffolding).isEmpty();
        }
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void the_content_inventory_lists_the_external_plugins_skills_with_no_api_change() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Registered registered =
                registerAndIngest(uniqueName("res"), createUpstream(manifestWithExternal("acme/tools")));

        SnapshotContentService.SnapshotContent content =
                contentService.content(registered.snapshot().id());

        SnapshotContentService.PluginContent tools = content.plugins().stream()
                .filter(plugin -> "tools".equals(plugin.name()))
                .findFirst()
                .orElseThrow();
        assertThat(tools.source()).isEqualTo("./_plugins/tools");
        assertThat(tools.skills())
                .extracting(SnapshotContentService.SkillInfo::name)
                .contains("tool");
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_client_cloning_the_approved_snapshot_receives_a_manifest_with_no_external_url() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Registered registered =
                registerAndIngest(uniqueName("res"), createUpstream(manifestWithExternal("acme/tools")));
        approve(registered.snapshot().id());

        Path clone = newWorkDir("clone");
        GitResult result = gitClone(facadeUrl(registered.marketplace().name(), newPat()), clone);

        assertThat(result.exitCode()).as(result.output()).isZero();
        String served = java.nio.file.Files.readString(clone.resolve(MANIFEST_PATH));
        assertThat(served).contains("./_plugins/tools").doesNotContain(FORGE.baseUrl());
        assertThat(clone.resolve("_plugins/tools/skills/tool/SKILL.md")).exists();
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_secret_planted_in_the_external_repository_is_found_by_vetting() throws Exception {
        // The payoff of rewriting before vetting rather than at publish time: the closure *is* the
        // commit, so the chain sees external content with no connector change and no bypass.
        FORGE.publish("acme/leaky", Map.of("DEPLOY.md", "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE\n"));
        Registered registered =
                registerAndIngest(uniqueName("res"), createUpstream(manifestWithExternal("acme/leaky")));

        VettingRepository.Run run =
                vettingRepository.latestRun(registered.snapshot().id()).orElseThrow();
        List<Finding> findings = run.verdicts().stream()
                .filter(verdict -> "secret-scan".equals(verdict.connector()))
                .findFirst()
                .orElseThrow()
                .findings();
        assertThat(findings).isNotEmpty().anySatisfy(finding -> assertThat(finding.location())
                .startsWith("_plugins/tools/DEPLOY.md:"));
    }

    @Test
    @SVCs({"SVC_GW_0155", "SVC_GW_0156"})
    void re_ingesting_unchanged_content_produces_the_same_snapshot() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        Snapshot again = ingestionService.ingest(registered.marketplace(), null);

        assertThat(again.id()).isEqualTo(registered.snapshot().id());
        assertThat(again.sha()).isEqualTo(registered.snapshot().sha());
    }

    @Test
    @SVCs({"SVC_GW_0155", "SVC_GW_0156"})
    void an_external_repository_that_moves_on_produces_a_new_composite_and_a_new_snapshot() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        Registered registered = registerAndIngest(uniqueName("res"), upstream);
        approve(registered.snapshot().id());

        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool, revised\n"));
        Snapshot moved = ingestionService.ingest(registered.marketplace(), null);

        assertThat(moved.sha()).isNotEqualTo(registered.snapshot().sha());
        assertThat(moved.state()).isEqualTo(Snapshot.HELD);
        // The approved snapshot is still what a client gets: a new upstream head never publishes.
        assertThat(snapshotRepository
                        .findById(registered.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0161"})
    void an_unreachable_source_rejects_the_snapshot_at_the_upstream_commit_and_leaves_nothing_grafted()
            throws Exception {
        Path upstream = createUpstream(manifestWithExternal("acme/absent"));
        String upstreamHead = headSha(upstream);

        Registered registered = registerAndIngest(uniqueName("res"), upstream);
        Snapshot snapshot = registered.snapshot();

        assertThat(snapshot.state()).isEqualTo(Snapshot.REJECTED);
        assertThat(snapshot.sha()).isEqualTo(upstreamHead);
        assertThat(snapshot.violation()).contains("tools").contains("could not be resolved");
        assertThat(snapshot.decidable()).isFalse();
        assertThat(servedFileOrNull(registered.marketplace(), snapshot.sha(), "_plugins/tools/skills/tool/SKILL.md"))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0161"})
    void a_transfer_that_dies_part_way_through_rejects_the_snapshot() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.truncate("/acme/tools");
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().sha()).isEqualTo(headSha(upstream));
    }

    @Test
    @SVCs({"SVC_GW_0161"})
    void a_failed_resolution_leaves_the_previously_approved_snapshot_served() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        Registered registered = registerAndIngest(uniqueName("res"), upstream);
        approve(registered.snapshot().id());
        String servedSha = registered.snapshot().sha();

        // The source disappears and upstream moves; the next ingest can resolve nothing.
        FORGE.truncate("/acme/tools");
        addUpstreamCommit(upstream, "later");
        Snapshot rejected = ingestionService.ingest(registered.marketplace(), null);

        assertThat(rejected.state()).isEqualTo(Snapshot.REJECTED);
        Path clone = newWorkDir("clone");
        GitResult result = gitClone(facadeUrl(registered.marketplace().name(), newPat()), clone);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(headSha(clone)).isEqualTo(servedSha);
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_that_leaves_the_origin_is_refused_and_the_target_is_never_contacted() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.publish("evil/elsewhere", Map.of("skills/evil/SKILL.md", "# Evil\n"));
        // Same server, a host spelling the policy has not pinned: if the guard failed, the request
        // would arrive here and the fixture's log would show it.
        FORGE.redirectTo("http://localhost:" + FORGE.port() + "/evil/elsewhere/info/refs", 1);
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        // "leaves the host", not "host": the origin pin is a second layer that also refuses this,
        // and its message contains the substring "host" inside "localhost". A mutant that removed
        // the redirect check therefore passed a weaker assertion — which is what mutation testing
        // is for.
        assertThat(registered.snapshot().violation()).contains("redirect that leaves the host");
        assertThat(FORGE.wasRequested("/evil/elsewhere/info/refs")).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_to_the_cloud_metadata_endpoint_is_refused() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.redirectTo("http://169.254.169.254/latest/meta-data/iam/security-credentials/", 1);
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().violation()).contains("redirect");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_chain_longer_than_the_maximum_is_refused() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.redirectTo(FORGE.baseUrl() + "/acme/tools/info/refs?service=git-upload-pack", 10);
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_that_sends_more_than_the_received_byte_budget_is_refused() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.flood("/acme/tools");
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().sha()).isEqualTo(headSha(upstream));
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_with_a_file_over_the_blob_budget_is_refused() throws Exception {
        FORGE.publish("acme/heavy", Map.of("skills/heavy/SKILL.md", "x".repeat(128 * 1024)));
        Path upstream = createUpstream(manifestWithExternal("acme/heavy"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().violation()).contains("budget").contains("file");
    }

    @Test
    @SVCs({"SVC_GW_0156"})
    void a_marketplace_that_already_uses_the_reserved_directory_is_refused() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream =
                createUpstream(manifestWithExternal("acme/tools"), Map.of("_plugins/planted/SKILL.md", "# Planted\n"));

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().violation()).contains("_plugins");
    }

    @Test
    @SVCs({"SVC_GW_0155"})
    void a_source_pinned_to_a_commit_is_refused_rather_than_resolved_elsewhere() throws Exception {
        String pinned = """
                {
                  "name": "test-marketplace",
                  "plugins": [
                    {"name": "tools", "source": {"source": "github", "repo": "acme/tools", "sha": "%s"}}
                  ]
                }
                """.formatted("a".repeat(40));
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(pinned);

        Registered registered = registerAndIngest(uniqueName("res"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().violation()).contains("sha");
    }

    @Test
    @SVCs({"SVC_GW_0155", "SVC_GW_0161"})
    void two_concurrent_ingestions_of_one_marketplace_produce_one_snapshot() throws Exception {
        FORGE.publish("acme/tools", Map.of("skills/tool/SKILL.md", "# Tool\n"));
        Path upstream = createUpstream(manifestWithExternal("acme/tools"));
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("res"),
                upstream.toAbsolutePath().toString(),
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Snapshot> ingest = () -> ingestionService.ingest(marketplace, null);
            Future<Snapshot> first = pool.submit(ingest);
            Future<Snapshot> second = pool.submit(ingest);

            Snapshot one = first.get();
            Snapshot two = second.get();
            assertThat(one.id()).isEqualTo(two.id());
            assertThat(snapshotRepository.listByMarketplace(marketplace.id())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every {@code refs/snapshots/*} the marketplace's quarantine repository holds. */
    private List<String> pinnedSnapshotRefs(Marketplace marketplace) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace.name())) {
            return quarantine.getRefDatabase().getRefsByPrefix("refs/snapshots/").stream()
                    .map(Ref::getName)
                    .toList();
        }
    }

    private String servedFile(Marketplace marketplace, String sha, String path) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace.name())) {
            return fileAt(quarantine, sha, path);
        }
    }

    private String servedFileOrNull(Marketplace marketplace, String sha, String path) throws IOException {
        try (Repository quarantine = storage.quarantine(marketplace.name())) {
            try (RevWalk walk = new RevWalk(quarantine)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
                try (TreeWalk tree = TreeWalk.forPath(quarantine, path, commit.getTree())) {
                    return tree == null
                            ? null
                            : new String(quarantine.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static String fileAt(Repository repository, String sha, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                assertThat(tree).as("path %s in %s", path, sha).isNotNull();
                return new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
