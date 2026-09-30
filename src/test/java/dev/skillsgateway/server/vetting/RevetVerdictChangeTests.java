package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rule deciding whether a re-vet's verdict reaches the ledger (GW_VETTING_0017), as the pure
 * function it is. Each case changes one thing the verdict row carries and must count as a change;
 * the one case that changes nothing must not.
 */
class RevetVerdictChangeTests {

    private static final Finding HIGH = new Finding("rule-a", Severity.HIGH, "a.md:1", "high");
    private static final Finding LOW = new Finding("rule-b", Severity.LOW, "b.md:1", "low");

    private static Vetter vetter(String name, String version) {
        return new Vetter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Verdict vet(SnapshotUnderVetting snapshot) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** A previous run in which secret-scan@1 failed on one high finding. */
    private static Optional<VettingRepository.Run> previous(String chain) {
        return Optional.of(new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.Outcome.BLOCKED,
                Instant.now(),
                Instant.now(),
                chain,
                List.of(new VettingRepository.VerdictView(
                        1L, "secret-scan", 0, VerdictState.FAIL, "1 finding(s); worst high", null, List.of(HIGH)))));
    }

    private static final Optional<VettingRepository.Run> BEFORE = previous("secret-scan@1,other@1;mode=run-all");

    @Test
    @SVCs({"SVC_GW_VETTING_0017.2"})
    void the_same_verdict_from_the_same_vetter_version_is_unchanged() {
        assertThat(VettingService.changedSince(BEFORE, vetter("secret-scan", "1"), Verdict.of(List.of(HIGH))))
                .isFalse();
        // A different finding with the same count and worst severity is the same row.
        Finding moved = new Finding("rule-a", Severity.HIGH, "c.md:9", "high elsewhere");
        assertThat(VettingService.changedSince(BEFORE, vetter("secret-scan", "1"), Verdict.of(List.of(moved))))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0017.2"})
    void a_different_state_count_or_worst_severity_is_a_change() {
        Vetter same = vetter("secret-scan", "1");
        assertThat(VettingService.changedSince(BEFORE, same, Verdict.disabled("secret-scan", "for marketplace 'm'")))
                .isTrue();
        assertThat(VettingService.changedSince(BEFORE, same, Verdict.of(List.of(HIGH, HIGH))))
                .isTrue();
        // Same state (fail) and count as before, but a worse finding.
        Finding critical = new Finding("rule-c", Severity.CRITICAL, "a.md:1", "critical");
        assertThat(VettingService.changedSince(BEFORE, same, Verdict.of(List.of(critical))))
                .isTrue();
        assertThat(VettingService.changedSince(BEFORE, same, Verdict.of(List.of(LOW))))
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0017.2"})
    void a_vetter_absent_from_the_previous_chain_or_at_a_new_version_is_a_change() {
        assertThat(VettingService.changedSince(BEFORE, vetter("secret-scan", "2"), Verdict.of(List.of(HIGH))))
                .isTrue();
        // In the chain but with no verdict in the previous run.
        assertThat(VettingService.changedSince(BEFORE, vetter("other", "1"), Verdict.pass()))
                .isTrue();
        assertThat(VettingService.changedSince(BEFORE, vetter("new-vetter", "1"), Verdict.pass()))
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0017.2"})
    void no_previous_run_means_every_verdict_is_a_change() {
        assertThat(VettingService.changedSince(Optional.empty(), vetter("secret-scan", "1"), Verdict.of(List.of(HIGH))))
                .isTrue();
        assertThat(VettingService.changedSince(previous(null), vetter("secret-scan", "1"), Verdict.of(List.of(HIGH))))
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0017.2"})
    void the_chain_mode_suffix_is_not_part_of_a_member() {
        // A chain recorded without a mode suffix still names its members.
        assertThat(VettingService.changedSince(
                        previous("secret-scan@1"), vetter("secret-scan", "1"), Verdict.of(List.of(HIGH))))
                .isFalse();
    }
}
