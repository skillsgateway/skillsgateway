package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jimisola.skillsgateway.approval.VettingBlockedException;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.vetting.Finding;
import io.github.jimisola.skillsgateway.vetting.Severity;
import io.github.jimisola.skillsgateway.vetting.VerdictState;
import io.github.jimisola.skillsgateway.vetting.VettingChain;
import io.github.jimisola.skillsgateway.vetting.VettingRepository;
import io.github.jimisola.skillsgateway.vetting.Waiver;
import io.github.jimisola.skillsgateway.vetting.WaiverEvaluation;
import io.github.jimisola.skillsgateway.vetting.WaiverRepository;
import io.github.jimisola.skillsgateway.vetting.WaiverScope;
import io.github.jimisola.skillsgateway.vetting.WaiverService;
import io.github.jimisola.skillsgateway.vetting.WaiverValidationException;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verification of vetting waivers (GW_0044–GW_0048).
 *
 * <p>A waiver is the one sanctioned way past a trust boundary, so these tests attack it rather
 * than demonstrate it: a waiver with no expiry, a waiver whose expiry has already passed, a waiver
 * for the wrong rule, the wrong commit, and the wrong path, a waiver that has lapsed, and a waiver
 * that has been revoked. Every one of them must suppress nothing.
 */
class WaiverTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";
    private static final String OTHER_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private WaiverRepository waiverRepository;

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private GitStorage storage;

    private Instant soon() {
        return Instant.now().plus(Duration.ofDays(7));
    }

    private Registered blockedSnapshot(String prefix) throws Exception {
        return registerAndIngest(
                uniqueName(prefix),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
    }

    /**
     * The mandatory fields, each omitted in turn. Nothing may be written for a refused request:
     * "the waiver was rejected but half-recorded" would be the worst of both worlds.
     */
    @Test
    @SVCs({"SVC_GW_0044"})
    void aWaiverWithoutJustificationApproverOrAFutureExpiryIsRefused() throws Exception {
        Registered blocked = blockedSnapshot("waivfields");
        long id = blocked.snapshot().id();
        long marketplaceId = blocked.snapshot().marketplaceId();

        // No expiry at all — the thing this whole feature exists to make impossible.
        assertThatThrownBy(() ->
                        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy", null, "alice"))
                .isInstanceOf(WaiverValidationException.class)
                .hasMessageContaining("expiry");

        // An expiry in the past would be a waiver that is born inactive; refused at the door.
        assertThatThrownBy(() -> waiverService.create(
                        id,
                        RULE,
                        WaiverScope.SNAPSHOT,
                        null,
                        "documented dummy",
                        Instant.now().minus(Duration.ofMinutes(1)),
                        "alice"))
                .isInstanceOf(WaiverValidationException.class)
                .hasMessageContaining("future");

        assertThatThrownBy(() -> waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "  ", soon(), "alice"))
                .isInstanceOf(WaiverValidationException.class)
                .hasMessageContaining("justification");

        assertThatThrownBy(() ->
                        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy", soon(), " "))
                .isInstanceOf(WaiverValidationException.class)
                .hasMessageContaining("identity");

        // A path scope that names nothing is a blanket override wearing a justification.
        assertThatThrownBy(() ->
                        waiverService.create(id, RULE, WaiverScope.PATH, "  ", "documented dummy", soon(), "alice"))
                .isInstanceOf(WaiverValidationException.class);
        assertThatThrownBy(() -> waiverService.create(
                        id, RULE, WaiverScope.PATH, "../../etc", "documented dummy", soon(), "alice"))
                .isInstanceOf(WaiverValidationException.class)
                .hasMessageContaining("..");

        // None of the refusals wrote anything.
        assertThat(waiverRepository.byMarketplace(marketplaceId)).isEmpty();

        Waiver waiver =
                waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "alice");

        assertThat(waiver.ruleId()).isEqualTo(RULE);
        assertThat(waiver.approvedBy()).isEqualTo("alice");
        assertThat(waiver.justification()).isEqualTo("documented dummy key");
        assertThat(waiver.expiresAt()).isNotNull().isAfter(Instant.now());
        // Snapshot scope is derived from the snapshot, so it cannot be pointed elsewhere.
        assertThat(waiver.scopeValue()).isEqualTo(blocked.snapshot().sha());
        assertThat(waiverRepository.byMarketplace(marketplaceId)).hasSize(1);
    }

    /** Every near-miss scope: right rule wrong commit, right rule wrong path, wrong rule entirely. */
    @Test
    @SVCs({"SVC_GW_0045"})
    void aWaiverSuppressesOnlyTheFindingItsScopeNames() throws Exception {
        Registered blocked = blockedSnapshot("waivscope");
        long id = blocked.snapshot().id();
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);

        // 1. The same content, a different rule.
        waiverService.create(id, "private-key-block", WaiverScope.SNAPSHOT, null, "unrelated rule", soon(), "alice");
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);

        // 2. The right rule, pinned to a commit this snapshot is not.
        waiverRepository.create(
                blocked.snapshot().marketplaceId(),
                RULE,
                WaiverScope.SNAPSHOT,
                OTHER_SHA,
                "accepted on another snapshot",
                "alice",
                soon());
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);

        // 3. The right rule under a path the finding is not under — including the prefix trap,
        //    where 'plugins/hell' must not cover 'plugins/hello/...'.
        waiverService.create(id, RULE, WaiverScope.PATH, "plugins/other", "unrelated skill", soon(), "alice");
        waiverService.create(id, RULE, WaiverScope.PATH, "plugins/hell", "prefix trap", soon(), "alice");
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);

        // 4. Finally the one that actually names this finding on this snapshot.
        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "alice");
        WaiverEvaluation.Effect effect = waiverService.evaluate(id);

        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);
        assertThat(effect.suppressions()).singleElement().satisfies(suppression -> assertThat(suppression.ruleId())
                .isEqualTo(RULE));
        assertThat(effect.uncovered()).isEmpty();
        // The recorded evidence is never rewritten by a waiver.
        assertThat(effect.recordedOutcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(vettingRepository.latestRun(id).orElseThrow().outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    /**
     * Expiry and revocation, both from the cleared state back to blocked, and both without any
     * scheduled pass having run. The effective outcome is a function of {@code now}, so this test
     * only has to move the waiver, never the clock of some background job.
     */
    @Test
    @SVCs({"SVC_GW_0046"})
    void anExpiredOrRevokedWaiverStopsSuppressingItsFinding() throws Exception {
        Registered blocked = blockedSnapshot("waivexpiry");
        long id = blocked.snapshot().id();
        String name = blocked.marketplace().name();

        // A waiver that lapses almost immediately: written valid, so creation cannot object.
        Waiver shortLived = waiverService.create(
                id,
                RULE,
                WaiverScope.SNAPSHOT,
                null,
                "temporary acceptance",
                Instant.now().plusMillis(600),
                "alice");
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);

        Instant lapsed = shortLived.expiresAt().plusMillis(1);
        // Evaluated directly at an instant past the expiry: no sleep, no scheduler, no cache.
        WaiverEvaluation.Effect afterExpiry = WaiverEvaluation.evaluate(
                vettingRepository.latestRun(id).orElseThrow(),
                waiverRepository.byMarketplace(blocked.snapshot().marketplaceId()),
                blocked.snapshot().sha(),
                lapsed);
        assertThat(afterExpiry.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(afterExpiry.suppressions()).isEmpty();
        assertThat(afterExpiry.uncovered())
                .extracting(WaiverEvaluation.UncoveredFinding::ruleId)
                .contains(RULE);
        assertThat(shortLived.active(lapsed)).isFalse();

        // And the real gate agrees once the expiry has genuinely passed.
        Thread.sleep(Duration.between(Instant.now(), shortLived.expiresAt()).toMillis() + 50);
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(VettingBlockedException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.HELD);
        assertThat(storage.publishedIfServing(name)).isEmpty();

        // Revocation is the same answer by a different route.
        Waiver revocable =
                waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "alice");
        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);

        waiverService.revoke(revocable.id(), "bob");

        assertThat(waiverService.evaluate(id).outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThatThrownBy(() -> approvalService.approve(id, "alice")).isInstanceOf(VettingBlockedException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.HELD);
        assertThat(storage.publishedIfServing(name)).isEmpty();
    }

    /** Creation, use at approval, revocation, and the sweep's expiry note — all in the ledger. */
    @Test
    @SVCs({"SVC_GW_0048"})
    void theLedgerRecordsWaiverCreationUseAndRevocation() throws Exception {
        Registered blocked = blockedSnapshot("waivledger");
        long id = blocked.snapshot().id();
        String name = blocked.marketplace().name();
        String sha = blocked.snapshot().sha();

        Waiver used = waiverService.create(
                id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key in fixtures", soon(), "alice");
        Waiver revoked = waiverService.create(
                id, "private-key-block", WaiverScope.PATH, "plugins/hello", "mistake", soon(), "alice");
        waiverService.revoke(revoked.id(), "bob");

        var approved = approvalService.approve(id, "alice");
        waiverService.recordUse(name, sha, "alice", approved.waiversApplied());

        List<Map<String, Object>> entries = fetchLogRepository.list().stream()
                .filter(entry -> name.equals(entry.get("marketplace")))
                .toList();

        assertThat(entries)
                .filteredOn(entry -> WaiverService.EVENT_CREATED.equals(entry.get("event")))
                .hasSize(2)
                .anySatisfy(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("alice");
                    assertThat(entry.get("sha")).isEqualTo(sha);
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("rule=" + RULE)
                            .contains("scope=snapshot:" + sha)
                            .contains("expires=");
                });
        assertThat(entries)
                .filteredOn(entry -> WaiverService.EVENT_REVOKED.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("bob");
                    assertThat(String.valueOf(entry.get("detail"))).contains("rule=private-key-block");
                });
        assertThat(entries)
                .filteredOn(entry -> WaiverService.EVENT_APPLIED.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("alice");
                    assertThat(entry.get("sha")).isEqualTo(sha);
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("waiver=" + used.id())
                            .contains("rule=" + RULE)
                            .contains("approvedBy=alice");
                });

        // The sweep announces a lapse once, and never a second time.
        Registered other = blockedSnapshot("waivsweep");
        waiverRepository.create(
                other.snapshot().marketplaceId(),
                RULE,
                WaiverScope.SNAPSHOT,
                other.snapshot().sha(),
                "already lapsed",
                "alice",
                Instant.now().minus(Duration.ofDays(1)));
        assertThat(waiverService.sweepExpired(50)).isPositive();
        assertThat(waiverService.sweepExpired(50)).isZero();
        assertThat(fetchLogRepository.list().stream()
                        .filter(entry -> other.marketplace().name().equals(entry.get("marketplace")))
                        .filter(entry -> WaiverService.EVENT_EXPIRED.equals(entry.get("event")))
                        .count())
                .isEqualTo(1);
    }

    /**
     * The evaluation rule, exhaustively and without a database: every verdict state crossed with
     * "the finding is waived" and "it is not". The property under attack is that waiving can only
     * ever remove an objection — it must never turn a clearing verdict into a blocking one, and it
     * must never clear a verdict that has no findings to name.
     */
    @Test
    @SVCs({"SVC_GW_0045"})
    void evaluationNeverClearsAVerdictThatHasNothingToWaive() {
        String sha = "abc123";
        Finding critical = new Finding(RULE, Severity.CRITICAL, "plugins/hello/DEPLOY.md:3", "planted key");
        Waiver covering = new Waiver(
                1L,
                1L,
                "m",
                RULE,
                WaiverScope.SNAPSHOT,
                sha,
                "justified",
                "alice",
                Instant.now(),
                Instant.now().plus(Duration.ofDays(1)),
                null,
                null,
                null);
        Instant now = Instant.now();

        for (VerdictState state : VerdictState.values()) {
            // With findings: a waiver covering all of them clears a blocking verdict, and leaves a
            // clearing one exactly as clearing as it was.
            WaiverEvaluation.Effect withWaiver =
                    WaiverEvaluation.evaluate(run(state, List.of(critical)), List.of(covering), sha, now);
            assertThat(withWaiver.outcome())
                    .as("state %s with its only finding waived", state)
                    .isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);

            WaiverEvaluation.Effect withoutWaiver =
                    WaiverEvaluation.evaluate(run(state, List.of(critical)), List.of(), sha, now);
            assertThat(withoutWaiver.outcome())
                    .as("state %s with nothing waived", state)
                    .isEqualTo(state.clearing() ? VettingChain.Outcome.CLEAR : VettingChain.Outcome.BLOCKED);

            // Without findings there is nothing a waiver can name, so a blocking verdict — PENDING
            // above all — stays blocking no matter how many waivers exist.
            WaiverEvaluation.Effect noFindings =
                    WaiverEvaluation.evaluate(run(state, List.of()), List.of(covering), sha, now);
            assertThat(noFindings.outcome())
                    .as("state %s with no findings at all", state)
                    .isEqualTo(state.clearing() ? VettingChain.Outcome.CLEAR : VettingChain.Outcome.BLOCKED);
        }

        // An empty run and a missing run stay blocked, waivers or not.
        assertThat(WaiverEvaluation.evaluate(emptyRun(), List.of(covering), sha, now)
                        .outcome())
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(WaiverEvaluation.evaluate(null, List.of(covering), sha, now).outcome())
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    private static VettingRepository.Run run(VerdictState state, List<Finding> findings) {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.aggregate(List.of(state)),
                Instant.now(),
                Instant.now(),
                "secret-scan@1",
                List.of(new VettingRepository.VerdictView(1L, "secret-scan", 0, state, null, null, findings)));
    }

    private static VettingRepository.Run emptyRun() {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.Outcome.BLOCKED,
                Instant.now(),
                Instant.now(),
                "secret-scan@1",
                List.of());
    }
}
