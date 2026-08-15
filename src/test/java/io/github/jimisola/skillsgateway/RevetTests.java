package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.vetting.RevetService;
import io.github.jimisola.skillsgateway.vetting.RevetVerdict;
import io.github.jimisola.skillsgateway.vetting.VettingChain;
import io.github.jimisola.skillsgateway.vetting.VettingRepository;
import io.github.jimisola.skillsgateway.vetting.WaiverScope;
import io.github.jimisola.skillsgateway.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verification of continuous re-vetting in its default, warn-only mode (GW_0049, GW_0051).
 *
 * <p>The property under attack here is that warn mode is genuinely inert. It is easy to write a
 * "grace mode" that still quietly changes something; these tests check the snapshot row, the
 * published refs and a real git clone after a violation, because a grace mode that unpublishes
 * anything at all is worse than no grace mode — an operator would have enabled the sweep believing
 * it could not take content away.
 */
class RevetTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private RevetService revetService;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private GitStorage storage;

    @Autowired
    private SkillsGatewayProperties properties;

    private static Instant soon() {
        return Instant.now().plus(Duration.ofDays(7));
    }

    /**
     * A snapshot that is approved and published although the chain objects to its content: the only
     * honest way to reach that state is the sanctioned one — a waiver — so the fixture uses it, and
     * then withdraws the waiver. That is exactly the "an expired waiver is a violation on the next
     * re-vet" path, arranged without waiting for a clock.
     */
    private Registered approvedWithLapsedWaiver(String prefix) throws Exception {
        Registered registered = registerAndIngest(
                uniqueName(prefix),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
        long id = registered.snapshot().id();
        var waiver =
                waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "temporary acceptance", soon(), "alice");
        approve(id);
        waiverService.revoke(waiver.id(), "alice");
        return registered;
    }

    /**
     * The sweep's selection rule: only approved snapshots, oldest evidence first, never more than
     * the batch. A sweep that took everything every tick would be a periodic self-inflicted load
     * spike; one that took an arbitrary subset would starve whichever snapshots it never reached.
     */
    @Test
    @SVCs({"SVC_GW_0049"})
    void theSweepRevetsTheLeastRecentlyVettedApprovedSnapshotsInBatches() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(uniqueName("revetsweep1"), upstream);
        Registered second = registerAndIngest(uniqueName("revetsweep2"), upstream);
        Registered third = registerAndIngest(uniqueName("revetsweep3"), upstream);
        Registered held = registerAndIngest(uniqueName("revetsweepheld"), upstream);
        Registered rejected = registerAndIngest(uniqueName("revetsweeprej"), upstream);
        approvalService.reject(rejected.snapshot().id(), "alice");
        approve(first.snapshot().id());
        approve(second.snapshot().id());
        approve(third.snapshot().id());

        // Re-vet the third by hand, so its evidence is newest and the sweep must not pick it while
        // older ones are waiting. This is also the manual on-demand pass under test.
        RevetService.RevetResult manual =
                revetService.revetSnapshot(third.snapshot().id(), "alice");

        assertThat(manual.classification()).isEqualTo(RevetVerdict.Classification.CLEAR);
        VettingRepository.Run manualRun = vettingRepository.run(manual.runId()).orElseThrow();
        assertThat(manualRun.trigger()).isEqualTo(VettingRepository.TRIGGER_REVET_MANUAL);
        // The chain identity is recorded, so a changed answer can be attributed to a changed chain.
        assertThat(manualRun.chain()).contains("secret-scan@").contains("prompt-injection@");
        // Vetting never moves the snapshot; only revocation does, and nothing was revoked.
        assertThat(snapshotRepository
                        .findById(third.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.APPROVED);

        // The whole queue, so the assertions are about this test's own fixtures rather than about
        // whichever other test's snapshots happen to share the database.
        List<Long> queue = snapshotRepository.dueRevet(Instant.now(), 500).stream()
                .map(Snapshot::id)
                .toList();

        assertThat(snapshotRepository.dueRevet(Instant.now(), 500))
                .extracting(Snapshot::state)
                .containsOnly(Snapshot.APPROVED);
        // Neither the held nor the rejected snapshot is ever a candidate: nothing else is served,
        // so nothing else could be retroactively quarantined.
        assertThat(queue)
                .doesNotContain(held.snapshot().id())
                .doesNotContain(rejected.snapshot().id())
                .contains(
                        first.snapshot().id(),
                        second.snapshot().id(),
                        third.snapshot().id());
        // Oldest evidence first: the freshly re-vetted third snapshot queues behind the two whose
        // only run is still the ingestion one.
        assertThat(queue.indexOf(third.snapshot().id()))
                .isGreaterThan(queue.indexOf(first.snapshot().id()))
                .isGreaterThan(queue.indexOf(second.snapshot().id()));
        // And a pass is bounded: it never takes the whole estate.
        assertThat(snapshotRepository.dueRevet(Instant.now(), 2)).hasSize(2);

        RevetService.PassResult pass = revetService.sweep(RevetService.SWEEP_ACTOR);

        assertThat(pass.revetted())
                .isPositive()
                .isLessThanOrEqualTo(properties.vetting().revet().batchSize());
        assertThat(pass.revoked()).isZero();
        assertThat(vettingRepository.latestRun(queue.getFirst()).orElseThrow().trigger())
                .isEqualTo(VettingRepository.TRIGGER_REVET_SCHEDULED);
    }

    /**
     * Warn mode, attacked from the outside: after a violation on published content, a real git
     * client must still be able to clone it. Reading the snapshot row alone would not catch a grace
     * mode that removed a ref and left the state behind.
     */
    @Test
    @SVCs({"SVC_GW_0051"})
    void warnModeRecordsTheViolationAndNeverUnpublishes() throws Exception {
        assertThat(revetService.mode()).isEqualTo(SkillsGatewayProperties.RevetMode.WARN);
        Registered registered = approvedWithLapsedWaiver("revetwarn");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();

        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");

        assertThat(result.classification()).isEqualTo(RevetVerdict.Classification.VIOLATION);
        assertThat(result.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(result.revoked()).isFalse();
        assertThat(result.mode()).isEqualTo(SkillsGatewayProperties.RevetMode.WARN);
        assertThat(result.uncovered()).extracting(effect -> effect.ruleId()).contains(RULE);

        // Nothing moved: the state, the refs and a real clone all agree.
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(snapshotRepository.findById(id).orElseThrow().revokedAt()).isNull();
        assertThat(storage.publishedIfServing(name)).isPresent();
        Path clone = newWorkDir("revetwarnclone");
        GitResult cloned = gitClone(facadeUrl(name, newPat()), clone.resolve("repo"));
        assertThat(cloned.exitCode()).as(cloned.output()).isZero();
        assertThat(headSha(clone.resolve("repo"))).isEqualTo(sha);

        // But it was announced: warn mode's only output is the record, so the record must be there.
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> name.equals(entry.get("marketplace")))
                .filteredOn(entry -> RevetService.EVENT_VIOLATION.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .contains("mode=WARN")
                        .contains("secret-scan"));
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> name.equals(entry.get("marketplace")))
                .noneSatisfy(entry -> assertThat(entry.get("event")).isEqualTo(RevetService.EVENT_REVOKED));
    }

    /** A re-vetting run that clears leaves everything alone and says so. */
    @Test
    void aCleanRevetChangesNothing() throws Exception {
        Registered registered = registerAndIngest(uniqueName("revetclean"), createUpstream(DEFAULT_MANIFEST));
        long id = registered.snapshot().id();
        approve(id);

        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");

        assertThat(result.classification()).isEqualTo(RevetVerdict.Classification.CLEAR);
        assertThat(result.revoked()).isFalse();
        assertThat(result.affected()).isEmpty();
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(registered.marketplace().name())).isPresent();
    }

    /**
     * A waiver recorded after the violation, followed by a fresh re-vet, clears it — the operator's
     * way out of warn-mode noise, and the same route a revoked snapshot takes back to approval.
     */
    @Test
    void aWaiverRecordedAfterAViolationClearsTheNextRevet() throws Exception {
        Registered registered = approvedWithLapsedWaiver("revetrewaive");
        long id = registered.snapshot().id();
        assertThat(revetService.revetSnapshot(id, "alice").classification())
                .isEqualTo(RevetVerdict.Classification.VIOLATION);

        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "alice");

        RevetService.RevetResult after = revetService.revetSnapshot(id, "alice");
        assertThat(after.classification()).isEqualTo(RevetVerdict.Classification.CLEAR);
        assertThat(after.outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);
        // The recorded evidence is untouched: the waiver changed the judgement, not the finding.
        assertThat(vettingRepository.run(after.runId()).orElseThrow().outcome())
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    /** Only served content is re-vetted on demand; a held or rejected snapshot is refused. */
    @Test
    void onlyApprovedSnapshotsCanBeRevetted() throws Exception {
        Registered held = registerAndIngest(uniqueName("revetheld"), createUpstream(DEFAULT_MANIFEST));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> revetService.revetSnapshot(held.snapshot().id(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only approved");
    }
}
