package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the evidence in front of a reviewer was produced by the chain their marketplace runs now
 * (GW_VETTING_0038).
 *
 * <p>Enabling a vetter re-runs nothing. For approved content the re-vetting sweep converges on it
 * (GW_VETTING_0012); at the approval gate nothing does, so a snapshot ingested before a chain change
 * can be approved on evidence the newly enabled vetter never produced. The defect is the silence
 * rather than the staleness — this is the fact that ends it. It decides nothing: approval is not
 * refused for it, and no configuration makes it refuse (GW_VETTING_0038.1).
 *
 * <p>Three states, not two. A run recorded before the chain identity was stamped carries no chain,
 * and both two-valued answers are false there: reporting it in force asserts something unknown, and
 * reporting it superseded raises an alarm over every such row that nobody can act on.
 *
 * <p>Derived on read, never stored. A stored marker would be wrong the instant the chain changed
 * again, and would have to be invalidated from every path that can change one.
 */
@Schema(description = "Whether a run's chain is the one its marketplace runs now")
public record ChainStaleness(
        @Schema(
                description = "IN_FORCE: the run was produced by the chain in force."
                        + " SUPERSEDED: it was produced by a different one, and both are named."
                        + " UNDETERMINED: there is no run, or the run records no chain identity —"
                        + " which is not the same as being current.",
                allowableValues = {"IN_FORCE", "SUPERSEDED", "UNDETERMINED"})
        State state,

        @Schema(description = "Identity of the chain that produced the run, or null when it recorded none")
        String runChain,

        @Schema(description = "Identity of the chain the marketplace runs now, or null when it could not be resolved")
        String currentChain) {

    public enum State {
        IN_FORCE,
        SUPERSEDED,
        UNDETERMINED
    }

    /**
     * The comparison. A blank identity on either side is undetermined rather than a difference:
     * nothing should be able to report an estate stale by failing to resolve its chain.
     */
    @Requirements({"GW_VETTING_0038"})
    public static ChainStaleness of(String runChain, String currentChain) {
        if (isBlank(runChain) || isBlank(currentChain)) {
            return new ChainStaleness(State.UNDETERMINED, blankToNull(runChain), blankToNull(currentChain));
        }
        return new ChainStaleness(
                runChain.equals(currentChain) ? State.IN_FORCE : State.SUPERSEDED, runChain, currentChain);
    }

    /** A snapshot the chain has never run against. Already blocked by the outcome rule. */
    @Requirements({"GW_VETTING_0038"})
    public static ChainStaleness noRun(String currentChain) {
        return new ChainStaleness(State.UNDETERMINED, null, blankToNull(currentChain));
    }

    /**
     * True only for a difference the gateway actually established. Undetermined is deliberately
     * false here, so that a caller acting on this predicate cannot turn "unknown" into an alarm.
     */
    public boolean stale() {
        return state == State.SUPERSEDED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
