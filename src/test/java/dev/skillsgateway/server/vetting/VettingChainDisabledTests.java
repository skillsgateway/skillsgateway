package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fail-closed aggregation rule with a disabled connector in the run (GW_0143), exercised as the
 * pure static function it is — no database, no Spring context. These are the adversarial cases for
 * the one change that could turn the administrative off-switch into a blanket approval: a run made
 * only of disabled verdicts must still block, because switching every control off proves nothing.
 */
class VettingChainDisabledTests {

    @Test
    @SVCs({"SVC_GW_0143"})
    void a_disabled_verdict_does_not_block_when_something_else_clears() {
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS, VerdictState.DISABLED)))
                .isEqualTo(VettingChain.Outcome.CLEAR);
        assertThat(VettingChain.aggregate(List.of(VerdictState.WARN, VerdictState.DISABLED, VerdictState.PASS)))
                .isEqualTo(VettingChain.Outcome.CLEAR);
    }

    @Test
    @SVCs({"SVC_GW_0143"})
    void disabling_every_connector_blocks_rather_than_clears() {
        // The load-bearing case: no positive clearing evidence, so no clear — an estate that has
        // switched everything off is blocked, not cleared.
        assertThat(VettingChain.aggregate(List.of(VerdictState.DISABLED))).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(VettingChain.aggregate(List.of(VerdictState.DISABLED, VerdictState.DISABLED, VerdictState.DISABLED)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    @SVCs({"SVC_GW_0143"})
    void a_disabled_verdict_never_rescues_a_failing_one() {
        // Disabling one connector must not clear a snapshot another connector still objects to.
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS, VerdictState.DISABLED, VerdictState.FAIL)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(VettingChain.aggregate(List.of(VerdictState.DISABLED, VerdictState.ERROR)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(VettingChain.aggregate(List.of(VerdictState.DISABLED, VerdictState.PENDING)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    @SVCs({"SVC_GW_0143"})
    void the_pre_existing_rules_are_unchanged() {
        // Regression armour: the states that existed before DISABLED aggregate exactly as before.
        assertThat(VettingChain.aggregate(List.of())).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS))).isEqualTo(VettingChain.Outcome.CLEAR);
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS, VerdictState.WARN)))
                .isEqualTo(VettingChain.Outcome.CLEAR);
        assertThat(VettingChain.aggregate(List.of(VerdictState.PASS, VerdictState.FAIL)))
                .isEqualTo(VettingChain.Outcome.BLOCKED);
    }

    @Test
    @SVCs({"SVC_GW_0143"})
    void disabled_is_neither_clearing_nor_blocking() {
        assertThat(VerdictState.DISABLED.clearing()).isFalse();
        assertThat(VerdictState.DISABLED.blocking()).isFalse();
        // Every other non-clearing state still blocks.
        assertThat(VerdictState.FAIL.blocking()).isTrue();
        assertThat(VerdictState.ERROR.blocking()).isTrue();
        assertThat(VerdictState.PENDING.blocking()).isTrue();
        assertThat(VerdictState.PASS.blocking()).isFalse();
        assertThat(VerdictState.WARN.blocking()).isFalse();
    }
}
