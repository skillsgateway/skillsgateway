package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.mirror.ForgeMirrorService;
import dev.skillsgateway.server.mirror.MirrorReconciliationSweep;
import dev.skillsgateway.server.mirror.MirrorReport;
import dev.skillsgateway.server.observability.MirrorMetrics;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The recurring reconciliation, and the two things it must never become (GW_FACADE_0025–GW_FACADE_0028).
 *
 * <p>Against a real bare repository over {@code file://}, so every push, deletion and
 * {@code ls-remote} is the transport a forge would see and nothing here touches the network. The
 * forge is "broken" by moving that repository aside on disk, which is the closest a test gets to an
 * outage without a fake: the URL still parses, the credential is still configured, and JGit fails
 * exactly where it would against a host that stopped answering.
 *
 * <p>One walk rather than one assertion per method, for the reason the first increment's suite gives:
 * the mirrored marketplace is a deployment setting fixed when the context starts, so the phases
 * share one marketplace and their order is part of what is being asserted. The two adversarial
 * phases are the point of the suite:
 *
 * <ul>
 *   <li><b>Phase B</b> — published storage left readable but answering short while the database
 *       still says approved. A reconciliation deletes what the served set lacks, so its honest
 *       conclusion here would be to empty the mirror, automatically and on a timer. It must refuse.
 *   <li><b>Phase C</b> — the forge broken <em>before</em> the revocation, so the revocation's own
 *       push exhausts its retries and leaves the mirror holding a snapshot the gateway has
 *       withdrawn. Only the sweep is then allowed to run: no approval, no revocation, no restart.
 * </ul>
 *
 * <p>The schedule is set to a day here and driven by hand, so that what is asserted is the
 * reconciliation rather than a timer; that the method is genuinely on Spring's schedule is asserted
 * separately against the container's own task registry.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.mirror.enabled=true",
            // Compressed so a failed push is observable without the suite waiting on backoff.
            "skills-gateway.mirror.max-attempts=2",
            "skills-gateway.mirror.retry-delay=10ms",
            "skills-gateway.mirror.timeout=5s",
            // Configured so that "the report carries no credential" means something.
            "skills-gateway.mirror.username=sweep-bot",
            "skills-gateway.mirror.token=sweep-secret",
            // On, but never fired by the clock: the walk drives it, so each phase observes exactly
            // the reconciliation it caused. The wiring to the clock is its own test below.
            "skills-gateway.mirror.sweep-enabled=true",
            "skills-gateway.mirror.sweep-interval=24h",
            "skills-gateway.mirror.sweep-initial-delay=24h",
            // Revocation is what the headline phase turns on, and only enforce mode performs one.
            "skills-gateway.vetting.revet.mode=enforce",
            "skills-gateway.roles.admins=alice"
        })
class ForgeMirrorSweepTests extends AbstractGatewayTest {

    /** Unique per JVM: the gateway's data directory outlives a run while its database does not. */
    private static final String MIRRORED = "swept" + Long.toString(System.nanoTime(), 36);

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    /** A reference the gateway never published: what somebody writing to the forge leaves. */
    private static final String ORPHAN_REF = "refs/snapshots/" + "b".repeat(40);

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
                        Files.createTempDirectory(root, "forge-mirror-sweep").resolve("mirror.git");
                try (Git ignored = Git.init()
                        .setBare(true)
                        .setDirectory(mirrorRepository.toFile())
                        .setInitialBranch("main")
                        .call()) {
                    // A forge repository is provisioned by hand in this increment; this stands in.
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
    private MirrorReconciliationSweep sweep;

    @Autowired
    private GitStorage storage;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private RevetService revetService;

    @Autowired
    private FetchLogRepository ledger;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    @SVCs({"SVC_GW_FACADE_0025", "SVC_GW_FACADE_0026", "SVC_GW_FACADE_0027", "SVC_GW_FACADE_0028"})
    void the_schedule_bounds_drift_without_ever_emptying_a_mirror_on_a_bad_read() throws Exception {
        assertThat(mirror.enabled()).isTrue();

        // ---- Phase A: approved and mirrored, which is the state everything else departs from.
        Path upstream = createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET));
        Registered registered = registerAndIngest(MIRRORED, upstream);
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        String snapshotRef = GitStorage.SNAPSHOT_REF_PREFIX + sha;

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
        assertThat(mirrorRefs()).containsOnlyKeys(GitStorage.SERVED_REF, snapshotRef);
        assertThat(mirrorEvents()).contains(ForgeMirrorService.EVENT_UPDATED);
        // What the mirror-updated entry says it did, rather than merely that it happened (GW_FACADE_0028).
        assertThat(mirrorDetails(ForgeMirrorService.EVENT_UPDATED))
                .anySatisfy(detail -> assertThat(detail).contains("pushed=2").contains(snapshotRef));

        // ---- Phase B: published storage readable but answering short, with the database still
        // saying this marketplace has approved content. A reconciliation deletes what the served
        // set lacks, so its honest conclusion here is "empty the mirror" — and on a timer that is a
        // silent total wipe. It must refuse instead, and the mirror must come through untouched.
        ObjectId tip = removeServedTipFromPublishedStorage();
        int ledgerRowsBefore = mirrorEvents().size();

        sweep.sweepNow();
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();

        assertThat(mirrorRefs())
                .as("a short read of published storage must never empty the mirror")
                .containsOnlyKeys(GitStorage.SERVED_REF, snapshotRef);
        assertThat(mirrorEvents()).contains(ForgeMirrorService.EVENT_REFUSED);
        // A refusal reports as a failed attempt, not as a fourth outcome value: a client that
        // falls through to "fine" on a value it does not recognise would swallow exactly this
        // signal. The distinction lives in the error, in the log and on the ledger instead.
        MirrorReport refused = mirror.report();
        assertThat(refused.lastAttemptOutcome()).isEqualTo(MirrorReport.FAILED);
        assertThat(refused.error()).startsWith(ForgeMirrorService.REFUSAL);
        assertThat(refused.inSync()).isFalse();
        // A refusal is never agreement: the outcome must not read as a successful repair.
        assertThat(mirrorEvents().subList(ledgerRowsBefore, mirrorEvents().size()))
                .doesNotContain(ForgeMirrorService.EVENT_REPAIRED, ForgeMirrorService.EVENT_UPDATED);

        // Restored, and the guard proves to be a refusal rather than a jam: the very next
        // reconciliation succeeds, finds nothing to do, and therefore writes nothing (GW_FACADE_0028).
        restoreServedTipInPublishedStorage(tip);
        int quietRowsBefore = mirrorEvents().size();
        sweep.sweepNow();
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();
        assertThat(mirror.report().lastAttemptOutcome()).isEqualTo(MirrorReport.OK);
        assertThat(mirror.report().inSync()).isTrue();
        assertThat(mirrorEvents())
                .as("a reconciliation that changed nothing must not write a ledger row")
                .hasSize(quietRowsBefore);

        // ---- Phase C: the forge broken *before* the revocation, so the revocation's own push
        // exhausts its retries and leaves the mirror holding content the gateway has withdrawn.
        Path parked = breakTheForge();
        waiverService.revoke(waiver.id(), "alice");
        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");
        assertThat(result.revoked()).isTrue();
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();

        // The gateway's own state moved regardless of the forge — that is GW_FACADE_0021 holding.
        assertThat(storage.publishedIfServing(MIRRORED)).isEmpty();
        assertThat(mirrorEvents()).contains(ForgeMirrorService.EVENT_FAILED);

        restoreTheForge(parked);
        // The mirror is now the thing this whole change exists to prevent: a withdrawn snapshot
        // still browsable, with nothing scheduled to remove it before this increment.
        assertThat(mirrorRefs()).containsKey(snapshotRef);
        MirrorReport stale = mirror.report();
        assertThat(stale.inSync()).isFalse();
        assertThat(stale.staleOnMirror()).contains(snapshotRef);
        assertThat(gauge(MirrorMetrics.STALE_REFS)).isGreaterThan(0);

        // Only the sweep runs. No approval, no revocation, no restart.
        sweep.sweepNow();
        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();

        assertThat(mirrorRefs())
                .as("the schedule must remove what the revocation's own push could not")
                .isEmpty();
        assertThat(mirror.report().inSync()).isTrue();
        // Named as a repair rather than as an update: nothing was approved or revoked when it ran.
        assertThat(mirrorEvents()).contains(ForgeMirrorService.EVENT_REPAIRED);
        assertThat(mirrorDetails(ForgeMirrorService.EVENT_REPAIRED)).anySatisfy(detail -> assertThat(detail)
                .contains(MirrorReconciliationSweep.REASON)
                .contains(snapshotRef));

        // ---- Metrics (GW_FACADE_0026): the divergence is readable without asking the mirror, and the
        // meters carry no marketplace, commit, principal or credential.
        assertThat(gauge(MirrorMetrics.STALE_REFS)).isZero();
        assertThat(gauge(MirrorMetrics.REACHABLE)).isEqualTo(1.0);
        assertThat(gauge(MirrorMetrics.SECONDS_SINCE_SUCCESS)).isGreaterThanOrEqualTo(0);
        assertThat(gauge(MirrorMetrics.RECONCILIATIONS_OK)).isGreaterThan(0);
        assertThat(gauge(MirrorMetrics.RECONCILIATIONS_FAILED)).isGreaterThan(0);
        assertThat(mirrorMeterTags()).isEmpty();
        assertThat(mirrorMeterNames())
                .allSatisfy(name -> assertThat(name).doesNotContain(MIRRORED).doesNotContain(sha));

        // ---- Phase D: the administrator's own reconciliation (GW_FACADE_0027), against drift written to
        // the forge directly — which is the case no approval or revocation would ever notice.
        seedOrphanOnMirror(sha);
        assertThat(mirrorRefs()).containsKey(ORPHAN_REF);

        mockMvc.perform(post("/api/mirror/reconcile").with(oidcLogin().idToken(token -> token.subject("mallory"))))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(
                        post("/api/mirror/reconcile").with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.inSync").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("sweep-secret").doesNotContain("sweep-bot");
        assertThat(mirrorRefs()).doesNotContainKey(ORPHAN_REF);
        assertThat(ledger.list().toString()).doesNotContain("sweep-secret");
    }

    /**
     * The bound is only real if a clock actually turns it, so the schedule itself is asserted
     * against the container's task registry rather than against the annotation source.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0025"})
    void the_reconciliation_is_registered_on_the_applications_schedule() {
        assertThat(scheduledTasks.getScheduledTasks().stream()
                        .map(ScheduledTask::toString)
                        .toList())
                .anySatisfy(task ->
                        assertThat(task).contains("MirrorReconciliationSweep").contains("sweep"));
    }

    /**
     * Leaves published storage readable and short: the served tip removed while the database still
     * holds the marketplace's approved snapshot. This is a backend that answered wrong, not one
     * that failed — which is exactly the case a reconciliation cannot tell from a marketplace that
     * legitimately stopped serving.
     */
    private ObjectId removeServedTipFromPublishedStorage() throws IOException {
        try (Repository published = storage.published(MIRRORED)) {
            ObjectId tip = published.resolve(GitStorage.SERVED_REF);
            assertThat(tip).isNotNull();
            RefUpdate removal = published.updateRef(GitStorage.SERVED_REF);
            removal.setForceUpdate(true);
            assertThat(removal.delete()).isIn(RefUpdate.Result.FORCED, RefUpdate.Result.NEW);
            return tip;
        }
    }

    private void restoreServedTipInPublishedStorage(ObjectId tip) throws IOException {
        try (Repository published = storage.published(MIRRORED)) {
            RefUpdate restore = published.updateRef(GitStorage.SERVED_REF);
            restore.setNewObjectId(tip);
            restore.setForceUpdate(true);
            assertThat(restore.forceUpdate()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
        }
    }

    /** The URL still parses and the credential is still set; there is simply nothing there. */
    private static Path breakTheForge() throws IOException {
        Path parked = mirrorRepository.resolveSibling("mirror.git.parked");
        Files.move(mirrorRepository, parked, StandardCopyOption.REPLACE_EXISTING);
        return parked;
    }

    private static void restoreTheForge(Path parked) throws IOException {
        Files.move(parked, mirrorRepository, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Drift nobody asked for: a reference written straight to the forge, inside served namespaces. */
    private static void seedOrphanOnMirror(String sha) throws IOException {
        try (Repository bare = openMirror()) {
            RefUpdate orphan = bare.updateRef(ORPHAN_REF);
            orphan.setNewObjectId(ObjectId.fromString(sha));
            orphan.setForceUpdate(true);
            assertThat(orphan.forceUpdate()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
        }
    }

    private static Repository openMirror() throws IOException {
        return Git.open(mirrorRepository.toFile()).getRepository();
    }

    private static Map<String, String> mirrorRefs() throws IOException {
        try (Repository bare = openMirror()) {
            return bare.getRefDatabase().getRefs().stream()
                    .filter(ref -> ref.getObjectId() != null && GitStorage.isServedRef(ref.getName()))
                    .collect(Collectors.toMap(
                            Ref::getName, ref -> ref.getObjectId().name()));
        }
    }

    private List<String> mirrorEvents() {
        return ledger.list().stream()
                .filter(entry -> MIRRORED.equals(entry.get("marketplace")))
                .map(entry -> String.valueOf(entry.get("event")))
                .filter(event -> event.startsWith("mirror-"))
                .toList();
    }

    private List<String> mirrorDetails(String event) {
        return ledger.list().stream()
                .filter(entry -> MIRRORED.equals(entry.get("marketplace")))
                .filter(entry -> event.equals(String.valueOf(entry.get("event"))))
                .map(entry -> String.valueOf(entry.get("detail")))
                .toList();
    }

    private double gauge(String name) {
        Meter meter = meters.find(name).meter();
        assertThat(meter).as("meter %s is registered", name).isNotNull();
        return meter.measure().iterator().next().getValue();
    }

    private List<String> mirrorMeterNames() {
        return meters.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("skills_gateway.mirror"))
                .toList();
    }

    private List<String> mirrorMeterTags() {
        return meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("skills_gateway.mirror"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .toList();
    }
}
