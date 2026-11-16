package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.ChainStaleness;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain.Outcome;
import dev.skillsgateway.server.vetting.VettingRepository.Run;
import dev.skillsgateway.server.vetting.VettingRepository.VerdictView;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverEvaluation.Effect;
import dev.skillsgateway.server.vetting.WaiverEvaluation.Suppression;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The candidacy and vetting halves of automatic approval, over data (GW_APPROVAL_0044.5–0044.7). */
class AutoApprovalEligibilityTests {

    private static final String BLOB = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";
    private static final String OTHER_BLOB = "1111111111111111111111111111111111111111";
    private static final List<String> ALL = List.of("license-scan", "prompt-injection", "secret-scan");

    // --- candidacy ---

    private static Snapshot snapshot(long id, String state, Instant deletedAt) {
        String sha = "0".repeat(40);
        return new Snapshot(
                id,
                1L,
                sha,
                sha,
                state,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                deletedAt,
                null,
                null);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.5"})
    void theNewestHeldSnapshotWithNoClosureIsACandidate() {
        assertThat(AutoApprovalEligibility.candidacy(snapshot(7, Snapshot.HELD, null), 7, 0))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.5"})
    void anOlderHeldSnapshotIsNotACandidate() {
        assertThat(AutoApprovalEligibility.candidacy(snapshot(6, Snapshot.HELD, null), 7, 0))
                .startsWith("not-newest");
    }

    @ParameterizedTest
    @ValueSource(strings = {Snapshot.REVOKED, Snapshot.APPROVED, Snapshot.REJECTED})
    @SVCs({"SVC_GW_APPROVAL_0044.5"})
    void aSnapshotThatIsNotHeldIsNotACandidate(String state) {
        assertThat(AutoApprovalEligibility.candidacy(snapshot(7, state, null), 7, 0))
                .startsWith("not-held");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.5"})
    void aDeletedSnapshotIsNotACandidate() {
        assertThat(AutoApprovalEligibility.candidacy(snapshot(7, Snapshot.HELD, Instant.now()), 7, 0))
                .startsWith("deleted");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.6"})
    void oneClosureMemberKeepsItHeldWhateverItsUrl() {
        assertThat(AutoApprovalEligibility.candidacy(snapshot(7, Snapshot.HELD, null), 7, 1))
                .startsWith("external-source");
    }

    // --- vetting ---

    private static Finding finding(String rule, Severity severity, String location, String blob) {
        return new Finding(rule, severity, location, "m", blob);
    }

    private static VerdictView verdict(String vetter, VerdictState state, Finding... findings) {
        return new VerdictView(0, vetter, 0, state, "", null, List.of(findings));
    }

    private static List<VerdictView> passing() {
        List<VerdictView> verdicts = new ArrayList<>();
        for (String vetter : ALL) {
            verdicts.add(verdict(vetter, VerdictState.PASS));
        }
        return verdicts;
    }

    private static List<VerdictView> replacing(List<VerdictView> verdicts, VerdictView replacement) {
        List<VerdictView> copy = new ArrayList<>(verdicts);
        copy.replaceAll(v -> v.vetter().equals(replacement.vetter()) ? replacement : v);
        return copy;
    }

    private static Run run(Outcome outcome, List<VerdictView> verdicts) {
        return new Run(1, 1, "ingestion", outcome, Instant.now(), Instant.now(), "chain", verdicts);
    }

    private static Effect effect(Outcome outcome, Suppression... suppressions) {
        return new Effect(outcome, outcome, List.of(suppressions), List.of(), List.of());
    }

    private static AutoApprovalEligibility.Vetting vet(Run run, Effect effect) {
        return vet(ChainStaleness.State.IN_FORCE, run, effect, null, ALL, true);
    }

    private static AutoApprovalEligibility.Vetting vet(
            ChainStaleness.State chain, Run run, Effect effect, Run baseline, List<String> required, boolean every) {
        return AutoApprovalEligibility.vetting(
                new AutoApprovalEligibility.VettingInputs(chain, run, effect, baseline, required, every));
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aClearRunInForceQualifies() {
        AutoApprovalEligibility.Vetting result = vet(run(Outcome.CLEAR, passing()), effect(Outcome.CLEAR));
        assertThat(result.qualifies()).as(result.refusal()).isTrue();
        assertThat(result.worst()).isNull();
        assertThat(result.disabledVetters()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void noRunKeepsItHeld() {
        assertThat(vet(ChainStaleness.State.UNDETERMINED, null, WaiverEvaluation.noRun(), null, ALL, true)
                        .refusal())
                .isNotNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = ChainStaleness.State.class,
            names = {"UNDETERMINED", "SUPERSEDED"})
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aChainNotInForceKeepsItHeld(ChainStaleness.State chain) {
        assertThat(vet(chain, run(Outcome.CLEAR, passing()), effect(Outcome.CLEAR), null, ALL, true)
                        .refusal())
                .startsWith("chain-not-in-force");
    }

    @ParameterizedTest
    @EnumSource(
            value = VerdictState.class,
            names = {"NOT_REACHED", "ERROR", "PENDING"})
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aVetterThatDidNotRunKeepsItHeldWhateverRequiredVettersSays(VerdictState state) {
        Run run = run(Outcome.CLEAR, replacing(passing(), verdict("license-scan", state)));
        assertThat(vet(ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR), null, ALL, true)
                        .refusal())
                .startsWith("vetter-not-run");
        assertThat(vet(ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR), null, List.of("secret-scan"), false)
                        .refusal())
                .startsWith("vetter-not-run");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void unsetRequiredVettersHoldsOnAnyDisabledVerdict() {
        Run run = run(Outcome.CLEAR, replacing(passing(), verdict("license-scan", VerdictState.DISABLED)));
        assertThat(vet(run, effect(Outcome.CLEAR)).refusal()).startsWith("required-vetter-disabled");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void unsetRequiredVettersHoldsOnADisabledVerdictFromAVetterItDoesNotName() {
        List<VerdictView> verdicts = new ArrayList<>(passing());
        verdicts.add(verdict("external-review", VerdictState.DISABLED));
        assertThat(vet(run(Outcome.CLEAR, verdicts), effect(Outcome.CLEAR)).refusal())
                .startsWith("required-vetter-disabled");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void everyVetterButSecretScanDisabledKeepsItHeld() {
        Run run = run(
                Outcome.CLEAR,
                List.of(
                        verdict("license-scan", VerdictState.DISABLED),
                        verdict("prompt-injection", VerdictState.DISABLED),
                        verdict("secret-scan", VerdictState.PASS)));
        assertThat(vet(run, effect(Outcome.CLEAR)).refusal()).startsWith("required-vetter-disabled");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aRequiredVetterDisabledKeepsItHeld() {
        Run run = run(Outcome.CLEAR, replacing(passing(), verdict("secret-scan", VerdictState.DISABLED)));
        assertThat(vet(ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR), null, List.of("secret-scan"), false)
                        .refusal())
                .startsWith("required-vetter-disabled");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void anotherVetterDisabledUnderARelaxedListQualifiesAndIsNamed() {
        Run run = run(Outcome.CLEAR, replacing(passing(), verdict("license-scan", VerdictState.DISABLED)));
        AutoApprovalEligibility.Vetting result =
                vet(ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR), null, List.of("secret-scan"), false);
        assertThat(result.qualifies()).as(result.refusal()).isTrue();
        assertThat(result.disabledVetters()).containsExactly("license-scan");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aRequiredVetterAbsentFromTheChainKeepsItHeld() {
        Run run = run(
                Outcome.CLEAR,
                List.of(verdict("license-scan", VerdictState.PASS), verdict("prompt-injection", VerdictState.PASS)));
        assertThat(vet(ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR), null, List.of("secret-scan"), false)
                        .refusal())
                .startsWith("required-vetter-absent");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void anUncoveredHighFindingKeepsItHeld() {
        Run run = run(
                Outcome.BLOCKED,
                replacing(
                        passing(),
                        verdict("secret-scan", VerdictState.FAIL, finding("aws-key", Severity.HIGH, "a.md:1", BLOB))));
        assertThat(vet(run, effect(Outcome.BLOCKED)).refusal()).startsWith("blocked");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void mediumFindingsOnlyQualify() {
        Run run = run(
                Outcome.CLEAR,
                replacing(
                        passing(),
                        verdict(
                                "prompt-injection",
                                VerdictState.WARN,
                                finding("odd", Severity.MEDIUM, "a.md:1", BLOB))));
        AutoApprovalEligibility.Vetting result = vet(run, effect(Outcome.CLEAR));
        assertThat(result.qualifies()).as(result.refusal()).isTrue();
        assertThat(result.worst()).isEqualTo(Severity.MEDIUM);
    }

    private static Run waivedHigh(String blob) {
        return run(
                Outcome.BLOCKED,
                replacing(
                        passing(),
                        verdict("secret-scan", VerdictState.FAIL, finding("aws-key", Severity.HIGH, "a.md:1", blob))));
    }

    private static Suppression suppression() {
        return new Suppression("secret-scan", "aws-key", "a.md:1", 42, "alice", Instant.now());
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aWaivedHighFindingPresentInTheBaselineInThatBlobQualifies() {
        AutoApprovalEligibility.Vetting result = vet(
                ChainStaleness.State.IN_FORCE,
                waivedHigh(BLOB),
                effect(Outcome.CLEAR_WITH_WAIVERS, suppression()),
                waivedHigh(BLOB),
                ALL,
                true);
        assertThat(result.qualifies()).as(result.refusal()).isTrue();
        assertThat(result.waiverIds()).containsExactly(42L);
        assertThat(result.worst()).isEqualTo(Severity.HIGH);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aWaivedHighFindingAbsentFromTheBaselineInThatBlobKeepsItHeld() {
        Effect effect = effect(Outcome.CLEAR_WITH_WAIVERS, suppression());
        assertThat(vet(ChainStaleness.State.IN_FORCE, waivedHigh(BLOB), effect, waivedHigh(OTHER_BLOB), ALL, true)
                        .refusal())
                .startsWith("waived-finding-new");
        assertThat(vet(
                                ChainStaleness.State.IN_FORCE,
                                waivedHigh(BLOB),
                                effect,
                                run(Outcome.CLEAR, passing()),
                                ALL,
                                true)
                        .refusal())
                .startsWith("waived-finding-new");
        assertThat(vet(ChainStaleness.State.IN_FORCE, waivedHigh(BLOB), effect, null, ALL, true)
                        .refusal())
                .startsWith("waived-finding-new");
        assertThat(vet(ChainStaleness.State.IN_FORCE, waivedHigh(null), effect, waivedHigh(null), ALL, true)
                        .refusal())
                .startsWith("waived-finding-new");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void aWaivedMediumFindingNeedsNoBaseline() {
        Run run = run(
                Outcome.CLEAR_WITH_WAIVERS,
                replacing(
                        passing(),
                        verdict(
                                "secret-scan",
                                VerdictState.WARN,
                                finding("aws-key", Severity.MEDIUM, "a.md:1", BLOB))));
        AutoApprovalEligibility.Vetting result = vet(
                ChainStaleness.State.IN_FORCE, run, effect(Outcome.CLEAR_WITH_WAIVERS, suppression()), null, ALL, true);
        assertThat(result.qualifies()).as(result.refusal()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hook-config-unreadable", "hook-target-unscanned", "too-many-plugin-roots"})
    @SVCs({"SVC_GW_APPROVAL_0044.7"})
    void anUnreadSurfaceKeepsItHeldAtAnySeverity(String rule) {
        List<VerdictView> verdicts = new ArrayList<>(passing());
        verdicts.add(verdict("executable-surface", VerdictState.WARN, finding(rule, Severity.LOW, "h.json:1", BLOB)));
        assertThat(vet(run(Outcome.CLEAR, verdicts), effect(Outcome.CLEAR)).refusal())
                .startsWith("unread-surface");
    }
}
