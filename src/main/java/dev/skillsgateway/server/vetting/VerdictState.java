package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/**
 * What one connector concluded about one snapshot.
 *
 * <p>{@code ERROR} is deliberately distinct from {@code FAIL}: "this content is bad" and "the
 * connector broke" are different operational facts, even though {@link VettingChain} blocks on
 * both. {@code PENDING} is the seam for an asynchronous connector that answers by callback — no
 * built-in returns it in v1, but the aggregation already treats it as blocking, so the gate is
 * correct before the first async connector exists.
 *
 * <p>{@code DISABLED} is a third kind of thing again (GW_0149): not a conclusion the connector
 * reached, but the record that an administrator switched the connector off for this snapshot's
 * marketplace, so the chain skipped it. It is the only state that neither clears nor blocks —
 * {@link VettingChain} treats it as absent for the block decision but still requires positive
 * clearing evidence elsewhere in the run, so disabling every connector leaves the run blocked.
 */
@Schema(description = "A connector's conclusion about a snapshot")
public enum VerdictState {

    /** Nothing found. */
    PASS,

    /** Found something worth showing the reviewer that does not block approval. */
    WARN,

    /** Found something that blocks approval. */
    FAIL,

    /** The connector did not produce a verdict: it threw, or it exceeded its time limit. */
    ERROR,

    /** The connector was triggered and has not answered yet. Blocks, like every non-verdict. */
    PENDING,

    /**
     * An administrator disabled this connector for the snapshot's marketplace (GW_0149), so the
     * chain recorded this in its place instead of running it. Neither clears nor blocks.
     */
    DISABLED;

    /** Storage form: the lower-case name, matching the {@code vetting_verdicts.state} check. */
    public String stored() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VerdictState of(String stored) {
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }

    /** Only a pass or a warning lets a chain clear; see {@link VettingChain}. */
    public boolean clearing() {
        return this == PASS || this == WARN;
    }

    /**
     * Whether this state blocks the chain. Everything that is not clearing blocks, with the one
     * exception of {@link #DISABLED}: an administrator switching a connector off is a deliberate,
     * audited act, not an unanswered or broken verdict, so it must not fail the chain closed the
     * way a timeout or a crash does (GW_0149). Positive clearing evidence is still required
     * elsewhere in the run — see {@link VettingChain#aggregate}.
     */
    public boolean blocking() {
        return this != DISABLED && !clearing();
    }
}
