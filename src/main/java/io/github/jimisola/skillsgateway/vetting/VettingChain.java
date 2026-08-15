package io.github.jimisola.skillsgateway.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The fail-closed aggregation rule (GW_0038), and the only place it lives.
 *
 * <p>A chain run is {@link Outcome#CLEAR} <em>if and only if</em> it produced at least one verdict
 * and every verdict is {@link VerdictState#PASS} or {@link VerdictState#WARN}. Everything else is
 * {@link Outcome#BLOCKED}: a failing connector, a connector that crashed or timed out, an
 * asynchronous connector that has not answered — and an empty verdict list.
 *
 * <p>Empty-is-blocked is the load-bearing case. It is what makes "the chain never ran" — a
 * snapshot ingested before this feature existed, a run that died halfway, a connector list that was
 * misconfigured to nothing — fail closed rather than sail through the approval gate. There is no
 * input to this function that yields {@code CLEAR} without positive evidence.
 *
 * <p>This is a pure static function over states so that it can be tested exhaustively over every
 * combination of verdict states without a database, a repository, or a Spring context.
 */
public final class VettingChain {

    private VettingChain() {}

    /** The aggregate of one chain run. */
    @Schema(description = "Fail-closed aggregate of a chain run's verdicts")
    public enum Outcome {

        /** Every connector answered, and none of them objects. */
        CLEAR,

        /**
         * Nothing objects any more, but only because an active waiver is suppressing a finding
         * (GW_0045). Deliberately a different word from {@link #CLEAR}: a reviewer or an auditor
         * looking at a badge must never read an accepted risk as a clean chain. It is never
         * produced by {@link #aggregate(Collection)} — only by evaluating waivers over a run —
         * and it is never stored on the run, whose recorded outcome stays raw.
         */
        CLEAR_WITH_WAIVERS,

        /** Something objects, errored, is missing, or has not answered. Approval is gated. */
        BLOCKED;

        public String stored() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Outcome of(String stored) {
            return valueOf(stored.toUpperCase(Locale.ROOT));
        }

        public boolean blocked() {
            return this == BLOCKED;
        }
    }

    @Requirements({"GW_0038"})
    public static Outcome aggregate(Collection<VerdictState> states) {
        if (states == null || states.isEmpty()) {
            return Outcome.BLOCKED;
        }
        for (VerdictState state : states) {
            if (state == null || !state.clearing()) {
                return Outcome.BLOCKED;
            }
        }
        return Outcome.CLEAR;
    }

    /** {@link #aggregate(Collection)} over whole verdicts. */
    public static Outcome aggregateVerdicts(List<Verdict> verdicts) {
        return aggregate(
                verdicts == null ? null : verdicts.stream().map(Verdict::state).toList());
    }
}
