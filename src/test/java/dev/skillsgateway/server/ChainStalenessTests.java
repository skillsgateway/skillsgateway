package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.vetting.ChainStaleness;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/**
 * The comparison itself, with no Spring context and no database: it is one string equality and a
 * three-valued answer, and every way it can be wrong is cheap to state here.
 *
 * <p>The third value carries the weight. A run recorded before the chain identity was stamped has
 * no chain to compare, and both of the two obvious answers are lies: "current" asserts something
 * unknown, and "stale" raises an alarm over every such row that nobody can act on.
 */
class ChainStalenessTests {

    private static final String CHAIN = "prompt-injection@1,secret-scan@1;mode=run_all";

    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void an_identical_chain_is_the_chain_in_force() {
        ChainStaleness staleness = ChainStaleness.of(CHAIN, CHAIN);

        assertThat(staleness.state()).isEqualTo(ChainStaleness.State.IN_FORCE);
        assertThat(staleness.stale()).isFalse();
        assertThat(staleness.runChain()).isEqualTo(CHAIN);
        assertThat(staleness.currentChain()).isEqualTo(CHAIN);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void every_kind_of_difference_reads_as_a_different_chain_and_names_both() {
        String[] differing = {
            // A vetter enabled — the case the whole change exists for.
            "prompt-injection@1,secret-scan@1,license-scan@1;mode=run_all",
            // A vetter disabled.
            "secret-scan@1;mode=run_all",
            // New code looking at the same content.
            "prompt-injection@2,secret-scan@1;mode=run_all",
            // Order: two runs with identical versions can legitimately differ.
            "secret-scan@1,prompt-injection@1;mode=run_all",
            // Mode.
            "prompt-injection@1,secret-scan@1;mode=stop_after_fail",
        };

        for (String current : differing) {
            ChainStaleness staleness = ChainStaleness.of(CHAIN, current);

            assertThat(staleness.state()).as("chain in force: %s", current).isEqualTo(ChainStaleness.State.SUPERSEDED);
            assertThat(staleness.stale()).isTrue();
            assertThat(staleness.runChain()).isEqualTo(CHAIN);
            assertThat(staleness.currentChain()).isEqualTo(current);
        }
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void a_run_that_records_no_chain_is_undetermined_rather_than_either_answer() {
        for (String runChain : new String[] {null, "", "   "}) {
            ChainStaleness staleness = ChainStaleness.of(runChain, CHAIN);

            assertThat(staleness.state())
                    .as("run chain: %s", runChain == null ? "null" : "'" + runChain + "'")
                    .isEqualTo(ChainStaleness.State.UNDETERMINED);
            // The convenience predicate must not collapse unknown into "stale": callers that act
            // on it would raise an alarm over rows that predate the identity stamp.
            assertThat(staleness.stale()).isFalse();
        }
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void no_run_at_all_is_undetermined_and_names_no_run_chain() {
        ChainStaleness staleness = ChainStaleness.noRun(CHAIN);

        assertThat(staleness.state()).isEqualTo(ChainStaleness.State.UNDETERMINED);
        assertThat(staleness.stale()).isFalse();
        assertThat(staleness.runChain()).isNull();
        assertThat(staleness.currentChain()).isEqualTo(CHAIN);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0038"})
    void an_unresolvable_current_chain_is_undetermined_not_a_difference() {
        // Nothing should be able to report every run stale by failing to resolve the chain.
        for (String current : new String[] {null, ""}) {
            assertThat(ChainStaleness.of(CHAIN, current).state()).isEqualTo(ChainStaleness.State.UNDETERMINED);
        }
    }
}
