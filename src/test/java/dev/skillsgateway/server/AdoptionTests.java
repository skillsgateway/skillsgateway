package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.adoption.SnapshotContentResolver;
import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adoption, staleness and presence reporting off the fetch ledger (GW_OBSERVABILITY_0001, GW_OBSERVABILITY_0002,
 * GW_OBSERVABILITY_0006, GW_OBSERVABILITY_0007) and the always-recorded gateway metrics (GW_OBSERVABILITY_0003). All fetches here are real git clones through the facade, so the
 * ledger rows under aggregation are exactly the rows production writes; the authorization walk of
 * the reads lives in RoleEnforcementTests, whose enforcing context classifies them as
 * privileged reads.
 */
// Authorization is always enforced (GW_AUTH_0025), so this suite names the principal it acts as --
// "auditor", declared once for the whole family in AbstractNamedAdminsTest.
class AdoptionTests extends AbstractNamedAdminsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shapes VettingTests plants: a formed AWS key id and a PEM header, belonging to nobody. */
    private static final String PLANTED_SECRETS = """
            AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE

            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAxGZQ0000000000000000000000000000000000000000000000
            -----END RSA PRIVATE KEY-----
            """;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GitStorage gitStorage;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0001"})
    void the_adoption_report_aggregates_the_windowed_ledger_per_marketplace_sha_and_identity() throws Exception {
        String name = uniqueName("adopt");
        Registered fixture = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        String sha = fixture.snapshot().sha();
        approve(fixture.snapshot().id());

        String alice = uniqueName("ada");
        String bob = uniqueName("bob");
        clone(name, alice);
        clone(name, bob);

        // A fetch older than the window, planted directly on the ledger: the report must not
        // count it at 30 days and must count it at 365.
        jdbc.sql("INSERT INTO fetch_log (ts, source, principal, marketplace, event, ref, sha)"
                        + " VALUES (:ts, '127.0.0.1', :principal, :marketplace, 'upload-pack',"
                        + " 'refs/heads/main', :sha)")
                .param("ts", OffsetDateTime.now().minus(Duration.ofDays(40)))
                .param("principal", uniqueName("oldtimer"))
                .param("marketplace", name)
                .param("sha", sha)
                .update();

        JsonNode windowed = marketplaceEntry(adoption("/api/v1/adoption?days=30"), name);
        assertThat(windowed.get("fetches").asLong()).isEqualTo(2);
        assertThat(windowed.get("identities").asLong()).isEqualTo(2);
        assertThat(windowed.get("servedSha").asText()).isEqualTo(sha);
        assertThat(windowed.get("lastFetch").asText()).isNotEmpty();
        JsonNode breakdown = windowed.get("snapshots");
        assertThat(breakdown).hasSize(1);
        assertThat(breakdown.get(0).get("sha").asText()).isEqualTo(sha);
        assertThat(breakdown.get(0).get("fetches").asLong()).isEqualTo(2);
        assertThat(breakdown.get(0).get("identities").asLong()).isEqualTo(2);
        assertThat(breakdown.get(0).get("current").asBoolean()).isTrue();

        JsonNode yearWide = marketplaceEntry(adoption("/api/v1/adoption?days=365"), name);
        assertThat(yearWide.get("fetches").asLong()).isEqualTo(3);
        assertThat(yearWide.get("identities").asLong()).isEqualTo(3);
    }

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0002"})
    void staleness_names_exactly_the_identities_not_on_the_served_tip_retracted_content_included() throws Exception {
        String name = uniqueName("stale");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        String v1 = first.snapshot().sha();
        approve(first.snapshot().id());

        String alice = uniqueName("ada");
        String bob = uniqueName("bob");
        clone(name, alice); // alice received v1

        addUpstreamCommit(upstream, "newer content");
        long secondId = ingestionService.ingest(first.marketplace(), null).id();
        String v2 = approve(secondId).sha();
        clone(name, bob); // bob received the new tip

        List<JsonNode> stale = stalenessOf(name);
        assertThat(stale).hasSize(1);
        JsonNode entry = stale.get(0);
        assertThat(entry.get("principal").asText()).isEqualTo(alice);
        assertThat(entry.get("sha").asText()).isEqualTo(v1);
        assertThat(entry.get("servedSha").asText()).isEqualTo(v2);
        assertThat(entry.get("lastFetch").asText()).isNotEmpty();

        // The marketplace stops serving entirely: every holder of its content is now stale, and
        // there is no tip to diverge from — that is what a retraction leaves behind.
        assertThat(gitStorage.unpublish(name, v2)).isTrue();
        List<JsonNode> afterRetraction = stalenessOf(name);
        assertThat(afterRetraction)
                .extracting(node -> node.get("principal").asText())
                .containsExactlyInAnyOrder(alice, bob);
        for (JsonNode holder : afterRetraction) {
            assertThat(holder.get("servedSha").isNull()).isTrue();
        }
    }

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0003"})
    void the_skills_gateway_metrics_are_recorded_with_export_left_at_its_disabled_default() throws Exception {
        // The context runs with the repository's default telemetry posture: no export enabled.
        String name = uniqueName("metrics");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered fixture = registerAndIngest(name, upstream);
        approve(fixture.snapshot().id());
        addUpstreamCommit(upstream, "to be rejected");
        long rejectedId = ingestionService.ingest(fixture.marketplace(), null).id();
        approvalService.reject(rejectedId, "alice");
        clone(name, uniqueName("carol"));

        Timer ingestion = meterRegistry
                .find(GatewayMetrics.INGESTION)
                .tag("outcome", "success")
                .timer();
        assertThat(ingestion).isNotNull();
        assertThat(ingestion.count()).isGreaterThanOrEqualTo(2);

        Timer approvals = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "approve")
                .tag("outcome", "success")
                .timer();
        assertThat(approvals).isNotNull();
        assertThat(approvals.count()).isGreaterThanOrEqualTo(1);
        Timer rejections = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "reject")
                .tag("outcome", "success")
                .timer();
        assertThat(rejections).isNotNull();
        assertThat(rejections.count()).isGreaterThanOrEqualTo(1);

        Counter uploads = meterRegistry
                .find(GatewayMetrics.FACADE_FETCHES)
                .tag("event", "upload-pack")
                .counter();
        Counter advertisements = meterRegistry
                .find(GatewayMetrics.FACADE_FETCHES)
                .tag("event", "info-refs")
                .counter();
        assertThat(uploads).isNotNull();
        assertThat(uploads.count()).isGreaterThanOrEqualTo(1);
        assertThat(advertisements).isNotNull();
        assertThat(advertisements.count()).isGreaterThanOrEqualTo(1);

        // The observation never changes the observed behavior: a vetting-blocked approval still
        // surfaces its refusal, and lands on the error side of the decision timer.
        Registered blocked = registerAndIngest(
                uniqueName("metricsblocked"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRETS)));
        assertThatThrownBy(() -> approvalService.approve(blocked.snapshot().id(), "alice"))
                .isInstanceOf(VettingBlockedException.class);
        Timer refused = meterRegistry
                .find(GatewayMetrics.APPROVAL)
                .tag("decision", "approve")
                .tag("outcome", "error")
                .timer();
        assertThat(refused).isNotNull();
        assertThat(refused.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0006"})
    void presence_names_served_skills_with_their_holders_and_reports_unresolvable_snapshots_rather_than_dropping_them()
            throws Exception {
        String name = uniqueName("presence");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        String v1 = approve(first.snapshot().id()).sha();
        String alice = uniqueName("ada");
        clone(name, alice); // alice holds v1: hello

        addSkill(upstream, "extra");
        String v2 =
                approve(ingestionService.ingest(first.marketplace(), null).id()).sha();
        String bob = uniqueName("bob");
        clone(name, bob); // bob holds v2: hello and extra

        // Held, never served: no report may name what it adds.
        addSkill(upstream, "unreleased");
        ingestionService.ingest(first.marketplace(), null);

        // A delivered SHA whose objects are gone, and one whose manifest does not parse.
        String reclaimed = "0".repeat(24) + Long.toHexString(System.nanoTime() | (1L << 60));
        String carol = uniqueName("carol");
        plantFetch(name, carol, reclaimed, OffsetDateTime.now());
        String broken = commitBrokenManifest(name);
        String dave = uniqueName("dave");
        plantFetch(name, dave, broken, OffsetDateTime.now());

        JsonNode report = adoption("/api/v1/adoption/presence");
        JsonNode hello = skillEntry(report, name, "hello");
        assertThat(hello.get("plugin").asText()).isEqualTo("hello");
        assertThat(hello.get("path").asText()).isEqualTo("plugins/hello/skills/hello/SKILL.md");
        assertThat(hello.get("identitiesHolding").asLong()).isEqualTo(2);
        assertThat(hello.get("snapshotsDelivering").asInt()).isEqualTo(2);
        assertThat(hello.get("firstDelivered").asText()).isNotEmpty();
        assertThat(hello.get("snapshots"))
                .extracting(node -> node.get("sha").asText())
                .containsExactlyInAnyOrder(v1, v2);

        JsonNode extra = skillEntry(report, name, "extra");
        assertThat(extra.get("identitiesHolding").asLong()).isEqualTo(1);
        assertThat(extra.get("snapshotsDelivering").asInt()).isEqualTo(1);
        assertThat(extra.get("snapshots").get(0).get("sha").asText()).isEqualTo(v2);
        assertThat(extra.get("snapshots").get(0).get("current").asBoolean()).isTrue();

        assertThat(skillsOf(report, name))
                .extracting(node -> node.get("skill").asText())
                .containsExactlyInAnyOrder("hello", "extra");

        List<JsonNode> unresolved = new ArrayList<>();
        for (JsonNode entry : report.get("unresolved")) {
            if (name.equals(entry.get("marketplace").asText())) {
                unresolved.add(entry);
            }
        }
        assertThat(unresolved)
                .extracting(node -> node.get("sha").asText())
                .containsExactlyInAnyOrder(reclaimed, broken);
        for (JsonNode entry : unresolved) {
            assertThat(entry.get("identitiesHolding").asLong()).isEqualTo(1);
        }

        // since drops holders whose latest fetch predates it, never the deliveries themselves.
        double hitsBefore = cacheCount("hit");
        double missesBefore = cacheCount("miss");
        String future = Instant.now().plus(Duration.ofDays(1)).toString();
        JsonNode bounded = adoption("/api/v1/adoption/presence?since=" + future);
        assertThat(bounded.get("since").asText()).isNotEmpty();
        assertThat(skillEntry(bounded, name, "hello").get("identitiesHolding").asLong())
                .isZero();

        // The second pass reads every resolvable SHA from the cache; only the unresolvable miss again.
        assertThat(cacheCount("hit") - hitsBefore).isGreaterThanOrEqualTo(2);
        assertThat(cacheCount("miss") - missesBefore)
                .isEqualTo(bounded.get("unresolved").size());
    }

    private double cacheCount(String result) {
        Counter counter = meterRegistry
                .find(SnapshotContentResolver.CACHE)
                .tag("result", result)
                .counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0006"})
    void a_manifest_repeating_a_plugin_name_counts_each_snapshot_once_per_skill() throws Exception {
        String name = uniqueName("repeat");
        registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        String sha = commitInQuarantine(
                name,
                Map.of(
                        ".claude-plugin/marketplace.json",
                        """
                        {"name": "repeat", "owner": {"name": "Test"}, "plugins": [
                          {"name": "twice", "source": "./a"}, {"name": "twice", "source": "./b"}]}
                        """,
                        "a/skills/same/SKILL.md",
                        CONFORMANT_SKILL,
                        "b/skills/same/SKILL.md",
                        CONFORMANT_SKILL));
        plantFetch(name, uniqueName("eve"), sha, OffsetDateTime.now());

        JsonNode same = skillEntry(adoption("/api/v1/adoption/presence"), name, "same");
        assertThat(same.get("identitiesHolding").asLong()).isEqualTo(1);
        assertThat(same.get("snapshotsDelivering").asInt()).isEqualTo(1);
        assertThat(same.get("snapshots")).hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0007"})
    void the_presence_report_says_in_its_payload_that_it_measures_presence_uniformly_per_snapshot() throws Exception {
        String name = uniqueName("uniform");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        addSkill(upstream, "second");
        Registered fixture = registerAndIngest(name, upstream);
        String sha = approve(fixture.snapshot().id()).sha();
        clone(name, uniqueName("ada"));
        clone(name, uniqueName("bob"));

        JsonNode report = adoption("/api/v1/adoption/presence");
        assertThat(report.get("measure").asText()).isEqualTo("presence");
        assertThat(report.get("statement").asText()).contains("not invocation").contains("whole snapshot");
        assertThat(report.get("since").isNull()).isTrue();

        List<JsonNode> skills = skillsOf(report, name);
        assertThat(skills).hasSize(2);
        for (JsonNode skill : skills) {
            assertThat(skill.get("identitiesHolding").asLong()).isEqualTo(2);
            assertThat(skill.get("snapshots").get(0).get("sha").asText()).isEqualTo(sha);
            assertThat(skill.get("snapshots").get(0).get("identitiesHolding").asLong())
                    .isEqualTo(2);
            // Named for what it is: nothing in a skill row may read as a usage measure.
            assertThat(skill.has("fetches")).isFalse();
            assertThat(skill.has("invocations")).isFalse();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** One real clone through the facade, authenticated as {@code principal} via a fresh PAT. */
    private void clone(String marketplace, String principal) throws Exception {
        String pat = tokenService.create(principal, "adoption-test").token();
        GitResult result = gitClone(facadeUrl(marketplace, pat), newWorkDir("adoption-clone"));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    private JsonNode adoption(String uri) throws Exception {
        String body = mockMvc.perform(get(uri).with(oidcLogin().idToken(token -> token.subject("auditor"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(body);
    }

    private static JsonNode marketplaceEntry(JsonNode report, String marketplace) {
        for (JsonNode entry : report) {
            if (marketplace.equals(entry.get("marketplace").asText())) {
                return entry;
            }
        }
        throw new AssertionError("marketplace %s not in report %s".formatted(marketplace, report));
    }

    /** The staleness entries concerning one marketplace; other suites' fixtures are not ours. */
    private List<JsonNode> stalenessOf(String marketplace) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : adoption("/api/v1/adoption/staleness")) {
            if (marketplace.equals(entry.get("marketplace").asText())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Commits a second skill under the default plugin upstream. */
    private static void addSkill(Path upstream, String skill) throws Exception {
        Path file = upstream.resolve("plugins/hello/skills/" + skill + "/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CONFORMANT_SKILL.replace("name: hello", "name: " + skill));
        try (Git git = Git.open(upstream.toFile())) {
            git.add().addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("Test", "test@example.com");
            git.commit()
                    .setMessage("add " + skill + " " + uniqueName("fixture"))
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    private void plantFetch(String marketplace, String principal, String sha, OffsetDateTime ts) {
        jdbc.sql("INSERT INTO fetch_log (ts, source, principal, marketplace, event, ref, sha)"
                        + " VALUES (:ts, '127.0.0.1', :principal, :marketplace, 'upload-pack',"
                        + " 'refs/heads/main', :sha)")
                .param("ts", ts)
                .param("principal", principal)
                .param("marketplace", marketplace)
                .param("sha", sha)
                .update();
    }

    /** A commit in the marketplace's quarantine whose manifest is not JSON. */
    private String commitBrokenManifest(String marketplace) throws Exception {
        return commitInQuarantine(marketplace, Map.of(".claude-plugin/marketplace.json", "{ not json"));
    }

    /** A parentless commit of exactly {@code files} in the marketplace's quarantine, on no ref. */
    private String commitInQuarantine(String marketplace, Map<String, String> files) throws Exception {
        try (Repository repo = gitStorage.quarantine(marketplace);
                ObjectInserter inserter = repo.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            PersonIdent who = new PersonIdent("Test", "test@example.invalid", Instant.now(), ZoneOffset.UTC);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(who);
            commit.setCommitter(who);
            commit.setMessage("fixture " + uniqueName("fixture"));
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id.name();
        }
    }

    /** The presence rows of one marketplace; other suites' fixtures are not ours. */
    private static List<JsonNode> skillsOf(JsonNode report, String marketplace) {
        List<JsonNode> skills = new ArrayList<>();
        for (JsonNode entry : report.get("skills")) {
            if (marketplace.equals(entry.get("marketplace").asText())) {
                skills.add(entry);
            }
        }
        return skills;
    }

    private static JsonNode skillEntry(JsonNode report, String marketplace, String skill) {
        return skillsOf(report, marketplace).stream()
                .filter(entry -> skill.equals(entry.get("skill").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("skill %s/%s not in %s".formatted(marketplace, skill, report)));
    }
}
