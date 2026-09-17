package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The short-circuit rules as the pure functions they are — no database, no Spring context
 * (GW_VETTING_0032).
 *
 * <p>These are the adversarial cases for the one change that could make a truncated run read as a
 * clean one. The load-bearing assertion is the last group: a run carrying a not-reached verdict has
 * a blocked effective outcome however many of its findings a waiver covers, because the vetters it
 * names never looked at the content.
 */
class VettingChainShortCircuitTests {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    @SVCs({"SVC_GW_VETTING_0032.2"})
    void not_reached_is_neither_clearing_nor_blocking_and_is_its_own_state() {
        assertThat(VerdictState.NOT_REACHED.clearing()).isFalse();
        assertThat(VerdictState.NOT_REACHED.blocking()).isFalse();
        // Distinct from the administrator's decision it is easily confused with: the two are told
        // apart by the ledger and by the reviewer surface, so they must not be the same value.
        assertThat(VerdictState.NOT_REACHED).isNotEqualTo(VerdictState.DISABLED);
        assertThat(VerdictState.NOT_REACHED.stored()).isEqualTo("not_reached");
        assertThat(VerdictState.of("not_reached")).isEqualTo(VerdictState.NOT_REACHED);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032"})
    void a_stopped_chain_aggregates_blocked_and_never_clear() {
        // The shape a real stop-after-fail run has: one failure, then vetters that never ran.
        assertThat(VettingChain.aggregate(
                        List.of(VerdictState.FAIL, VerdictState.NOT_REACHED, VerdictState.NOT_REACHED)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        // With something cleared before the failure, it still blocks: the failure is the answer.
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS, VerdictState.FAIL, VerdictState.NOT_REACHED)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        // A run of nothing but absences proves nothing, so it blocks — the DISABLED rule, extended.
        assertThat(VettingChain.aggregate(List.of(VerdictState.NOT_REACHED))).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(VettingChain.aggregate(List.of(VerdictState.NOT_REACHED, VerdictState.DISABLED)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.3"})
    void waiving_the_finding_that_stopped_the_chain_does_not_clear_the_run() {
        Finding secret = new Finding("aws-access-key-id", Severity.CRITICAL, "DEPLOY.md:3", "planted key");
        VettingRepository.Run run = run(
                verdict("secret-scan", 0, VerdictState.FAIL, List.of(secret)),
                notReached("prompt-injection", 1, "secret-scan"),
                notReached("license-scan", 2, "secret-scan"));

        Instant now = Instant.now();
        WaiverEvaluation.Effect effect = WaiverEvaluation.evaluate(run, List.of(waiver(secret.id(), now)), SHA, now);

        // The waiver really does suppress the finding — this is not an accident of it not matching.
        assertThat(effect.suppressions()).hasSize(1);
        // And the gate stays shut anyway: two vetters never looked at this content.
        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(effect.blocked()).isTrue();
        assertThat(effect.blockingVetters()).contains("prompt-injection", "license-scan");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.3"})
    void waiving_a_not_reached_verdicts_own_finding_does_not_promote_it() {
        // The bookkeeping finding a not-reached verdict carries is INFO, so re-deriving it through
        // the severity rule would produce a PASS. Accepting it must not turn a vetter that did not
        // look into positive clearing evidence.
        VettingRepository.Run run = run(
                verdict("secret-scan", 0, VerdictState.PASS, List.of()),
                notReached("prompt-injection", 1, "secret-scan"));

        Instant now = Instant.now();
        WaiverEvaluation.Effect effect =
                WaiverEvaluation.evaluate(run, List.of(waiver("vetter-not-reached", now)), SHA, now);

        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0032.3"})
    void a_complete_run_is_unaffected_by_the_rule() {
        // Regression armour: the rule must bite only on a run that actually stopped early.
        Finding secret = new Finding("aws-access-key-id", Severity.CRITICAL, "DEPLOY.md:3", "planted key");
        VettingRepository.Run run = run(
                verdict("secret-scan", 0, VerdictState.FAIL, List.of(secret)),
                verdict("prompt-injection", 1, VerdictState.PASS, List.of()));

        Instant now = Instant.now();
        WaiverEvaluation.Effect effect = WaiverEvaluation.evaluate(run, List.of(waiver(secret.id(), now)), SHA, now);

        assertThat(effect.outcome()).isEqualTo(VettingChain.Outcome.CLEAR_WITH_WAIVERS);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0033.1"})
    void the_resolved_order_is_total_names_first_and_drops_nothing() {
        List<Vetter> chain = List.of(
                new FakeVetter("secret-scan", 100),
                new FakeVetter("prompt-injection", 200),
                new FakeVetter("license-scan", 300),
                // Two vetters sharing a position: ties are broken by name, so the order is total.
                new FakeVetter("zeta", 400),
                new FakeVetter("alpha", 400));

        assertThat(names(VetterOrder.resolve(chain, List.of())))
                .containsExactly("secret-scan", "prompt-injection", "license-scan", "alpha", "zeta");

        // Named first, in the order given; the rest follow in their configured positions. Nothing
        // an override does not name can be dropped by omission.
        assertThat(names(VetterOrder.resolve(chain, List.of("license-scan", "secret-scan"))))
                .containsExactly("license-scan", "secret-scan", "prompt-injection", "alpha", "zeta");

        // A name matching nothing is inert here; it is refused where it is set, not silently
        // applied in a way that would reorder the rest.
        assertThat(names(VetterOrder.resolve(chain, List.of("no-such-vetter", "zeta"))))
                .containsExactly("zeta", "secret-scan", "prompt-injection", "license-scan", "alpha");

        // Deterministic: the same inputs give the same answer, whatever order they arrive in.
        assertThat(names(VetterOrder.resolve(chain.reversed(), List.of())))
                .containsExactly("secret-scan", "prompt-injection", "license-scan", "alpha", "zeta");
    }

    private static List<String> names(List<Vetter> vetters) {
        return vetters.stream().map(Vetter::name).toList();
    }

    private static VettingRepository.Run run(VettingRepository.VerdictView... verdicts) {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.Outcome.BLOCKED,
                Instant.now(),
                Instant.now(),
                "secret-scan@1;mode=stop-after-fail",
                List.of(verdicts));
    }

    private static VettingRepository.VerdictView verdict(
            String vetter, int position, VerdictState state, List<Finding> findings) {
        return new VettingRepository.VerdictView(position + 1L, vetter, position, state, null, null, findings);
    }

    private static VettingRepository.VerdictView notReached(String vetter, int position, String stoppedBy) {
        Verdict verdict = Verdict.notReached(vetter, stoppedBy);
        return verdict(vetter, position, verdict.state(), verdict.findings());
    }

    private static Waiver waiver(String ruleId, Instant now) {
        return new Waiver(
                1L,
                1L,
                "market",
                ruleId,
                WaiverScope.SNAPSHOT,
                SHA,
                "documented dummy",
                "alice",
                now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofDays(7)),
                null,
                null,
                null);
    }

    /** A vetter that exists only to have a name and a position; it is never run here. */
    private record FakeVetter(String name, int order) implements Vetter {

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public Verdict vet(SnapshotUnderVetting snapshot) {
            throw new UnsupportedOperationException("this double is never run");
        }
    }
}
