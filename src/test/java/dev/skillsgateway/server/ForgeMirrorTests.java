package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.mirror.ForgeMirrorService;
import dev.skillsgateway.server.mirror.MirrorReport;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The read-only forge mirror against a real push target (GW_0169, GW_0171, GW_0172).
 *
 * <p>The target is a bare repository on disk reached over {@code file://}, so the push, the
 * deletion and the ls-remote are the real JGit transport operations a forge would see — and no test
 * here touches the network. What that costs is that the whole suite shares one marketplace name:
 * the mirrored marketplace is a deployment setting, fixed when the context starts, so a per-test
 * name is not expressible. Hence one walk rather than one assertion per method.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.mirror.enabled=true",
            // Compressed so a failed push is observable without the suite waiting on backoff.
            "skills-gateway.mirror.max-attempts=2",
            "skills-gateway.mirror.retry-delay=10ms",
            "skills-gateway.mirror.timeout=5s",
            // Configured so that "the report and the ledger do not contain it" means something.
            "skills-gateway.mirror.username=mirror-bot",
            "skills-gateway.mirror.token=mirror-secret",
            // This walk seeds drift behind the gateway's back and then asserts on it. The recurring
            // reconciliation (GW_0190) exists precisely to repair that, so leaving it on would put a
            // second actor inside the assertions. It has its own suite; no expectation here changes.
            "skills-gateway.mirror.sweep-enabled=false",
            // Revocation is what GW_0171 is about, and only enforce mode performs one.
            "skills-gateway.vetting.revet.mode=enforce",
            "skills-gateway.roles.admins=alice"
        })
class ForgeMirrorTests extends AbstractGatewayTest {

    /**
     * Unique per JVM, because the gateway's data directory outlives a run: a fixed name would meet
     * last run's published repository holding last run's snapshot references, against a database
     * that had forgotten them.
     */
    private static final String MIRRORED = "mirrored" + Long.toString(System.nanoTime(), 36);

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    /** A reference the gateway never published: what a revocation that missed the mirror leaves. */
    private static final String ORPHAN_REF = "refs/snapshots/" + "a".repeat(40);

    private static Path mirrorRepository;

    @DynamicPropertySource
    static void mirrorTarget(DynamicPropertyRegistry registry) {
        registry.add("skills-gateway.mirror.marketplace", () -> MIRRORED);
        registry.add("skills-gateway.mirror.url", () -> mirrorUrl());
    }

    private static String mirrorUrl() {
        try {
            if (mirrorRepository == null) {
                Path root = Path.of("target", "test-workdirs");
                Files.createDirectories(root);
                mirrorRepository =
                        Files.createTempDirectory(root, "forge-mirror").resolve("mirror.git");
                try (Git ignored = Git.init()
                        .setBare(true)
                        .setDirectory(mirrorRepository.toFile())
                        .setInitialBranch("main")
                        .call()) {
                    // Initialising creates it; a forge repository is provisioned by hand in this
                    // increment, which is what this fixture stands in for.
                }
            }
            return "file://" + mirrorRepository.toAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("could not create the mirror fixture", e);
        }
    }

    @Autowired
    private ForgeMirrorService mirror;

    @Autowired
    private GitStorage storage;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private RevetService revetService;

    @Autowired
    private FetchLogRepository ledger;

    /**
     * Approve, drift, revoke — the mirror's whole life, checked against the bare repository rather
     * than against the gateway's own opinion of what it pushed.
     *
     * <p>Ingesting a second snapshot and never approving it is the direct form of "quarantine is
     * never mirrored": it is not enough that approval happens to be the trigger, because a
     * reconciliation that read the wrong repository would push it anyway.
     */
    @Test
    @SVCs({"SVC_GW_0169", "SVC_GW_0171", "SVC_GW_0172"})
    void the_mirror_holds_exactly_what_is_served_through_approval_drift_and_revocation() throws Exception {
        assertThat(mirror.enabled()).isTrue();
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET));
        Registered registered = registerAndIngest(MIRRORED, upstream);
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();

        // Nothing is served yet, so nothing is mirrored: the snapshot exists only in quarantine.
        assertThat(mirrorRefs()).isEmpty();

        var waiver = waiverService.create(
                id,
                RULE,
                WaiverScope.SNAPSHOT,
                null,
                "temporary acceptance",
                Instant.now().plus(Duration.ofDays(7)),
                "alice");
        approve(id);
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();

        // The mirror holds the served set and nothing else, read back from the bare repository.
        assertThat(mirrorRefs())
                .containsOnlyKeys(GitStorage.SERVED_REF, GitStorage.SNAPSHOT_REF_PREFIX + sha)
                .containsValue(sha);
        assertThat(servedRefs()).isEqualTo(mirrorRefs());

        MirrorReport afterApproval = mirror.report();
        assertThat(afterApproval.enabled()).isTrue();
        assertThat(afterApproval.reachable()).isTrue();
        assertThat(afterApproval.inSync()).isTrue();
        assertThat(afterApproval.servedTip()).isEqualTo(sha);
        assertThat(afterApproval.mirrorTip()).isEqualTo(sha);
        assertThat(afterApproval.missingOnMirror()).isEmpty();
        assertThat(afterApproval.staleOnMirror()).isEmpty();
        assertThat(afterApproval.lastAttemptOutcome()).isEqualTo(MirrorReport.OK);
        assertThat(afterApproval.lastAttemptAt()).isNotNull();
        assertThat(ledgerEvents()).contains(ForgeMirrorService.EVENT_UPDATED);

        // A second snapshot, ingested and left held: quarantine is not a source the mirror reads.
        addUpstreamCommit(upstream, "second");
        Snapshot held = ingestionService.ingest(registered.marketplace(), null);
        assertThat(held.state()).isEqualTo(Snapshot.HELD);
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();
        assertThat(mirrorRefs()).doesNotContainValue(held.sha());

        // Drift seeded behind the gateway's back: a served ref removed, one it never published left
        // behind. This is what a revocation that failed to reach the mirror looks like from outside.
        try (Repository bare = openMirror()) {
            RefUpdate removal = bare.updateRef(GitStorage.SERVED_REF);
            removal.setForceUpdate(true);
            assertThat(removal.delete()).isIn(RefUpdate.Result.FORCED, RefUpdate.Result.NEW);
            RefUpdate orphan = bare.updateRef(ORPHAN_REF);
            orphan.setNewObjectId(ObjectId.fromString(sha));
            orphan.setForceUpdate(true);
            assertThat(orphan.forceUpdate()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
        }
        MirrorReport drifted = mirror.report();
        assertThat(drifted.reachable()).isTrue();
        assertThat(drifted.inSync()).isFalse();
        assertThat(drifted.missingOnMirror()).containsExactly(GitStorage.SERVED_REF);
        assertThat(drifted.staleOnMirror()).containsExactly(ORPHAN_REF);
        assertThat(drifted.mirrorTip()).isNull();
        // The drift report says nothing about what is served: the facade is unaffected.
        assertThat(storage.publishedIfServing(MIRRORED)).isPresent();

        // Revocation, and the reconciliation it triggers, takes the snapshot off both surfaces —
        // and repairs the seeded drift on the way, because it pushes the served set rather than a
        // delta.
        waiverService.revoke(waiver.id(), "alice");
        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");
        assertThat(result.revoked()).isTrue();
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();
        assertThat(storage.publishedIfServing(MIRRORED)).isEmpty();
        assertThat(mirrorRefs()).isEmpty();
        assertThat(mirror.report().inSync()).isTrue();
    }

    /**
     * The report is an administrator's read of an outbound integration, and it never carries the
     * push credential — which is configured in this suite precisely so "it is absent" means
     * something.
     */
    @Test
    @SVCs({"SVC_GW_0172"})
    void the_drift_report_is_admin_only_and_never_carries_the_push_credential() throws Exception {
        mockMvc.perform(get("/api/mirror/drift").with(oidcLogin().idToken(token -> token.subject("mallory"))))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(
                        get("/api/mirror/drift").with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.marketplace").value(MIRRORED))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("mirror-secret").doesNotContain("mirror-bot");
        assertThat(ledger.list().toString()).doesNotContain("mirror-secret");
    }

    private static Repository openMirror() throws IOException {
        return Git.open(mirrorRepository.toFile()).getRepository();
    }

    /** What the bare repository actually holds, in the namespaces the facade serves. */
    private static Map<String, String> mirrorRefs() throws IOException {
        try (Repository bare = openMirror()) {
            return bare.getRefDatabase().getRefs().stream()
                    .filter(ref -> ref.getObjectId() != null && GitStorage.isServedRef(ref.getName()))
                    .collect(java.util.stream.Collectors.toMap(
                            Ref::getName, ref -> ref.getObjectId().name()));
        }
    }

    private Map<String, String> servedRefs() throws IOException {
        try (Repository published = storage.published(MIRRORED)) {
            return published.getRefDatabase().getRefs().stream()
                    .filter(ref -> ref.getObjectId() != null && GitStorage.isServedRef(ref.getName()))
                    .collect(java.util.stream.Collectors.toMap(
                            Ref::getName, ref -> ref.getObjectId().name()));
        }
    }

    private List<String> ledgerEvents() {
        return ledger.list().stream()
                .filter(entry -> MIRRORED.equals(entry.get("marketplace")))
                .map(entry -> String.valueOf(entry.get("event")))
                .toList();
    }
}
