package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A vetting run that has not finished is blocked at the gate (GW_VETTING_0067), however its recorded
 * verdicts read: the verdicts it never recorded are the ones that would have objected.
 */
class UnfinishedRunTests {

    private static VettingRepository.Run run(Instant finishedAt) {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_INGESTION,
                VettingChain.Outcome.BLOCKED,
                Instant.now(),
                finishedAt,
                "secret-scan@1",
                List.of(new VettingRepository.VerdictView(
                        1L, "secret-scan", 0, VerdictState.PASS, null, null, List.of())));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0070"})
    void an_unfinished_run_with_only_passing_verdicts_is_blocked() {
        assertThat(WaiverEvaluation.evaluate(run(null), List.of(), "1".repeat(40), Instant.now())
                        .blocked())
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0070"})
    void the_same_run_once_finished_is_clear() {
        assertThat(WaiverEvaluation.evaluate(run(Instant.now()), List.of(), "1".repeat(40), Instant.now())
                        .blocked())
                .isFalse();
    }
}
