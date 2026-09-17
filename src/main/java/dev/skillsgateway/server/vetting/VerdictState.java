package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/**
 * What one vetter concluded about one snapshot.
 *
 * <p>{@code ERROR} is deliberately distinct from {@code FAIL}: "this content is bad" and "the
 * vetter broke" are different operational facts, even though {@link VettingChain} blocks on
 * both. {@code PENDING} is the seam for an asynchronous vetter that answers by callback — no
 * built-in returns it in v1, but the aggregation already treats it as blocking, so the gate is
 * correct before the first async vetter exists.
 *
 * <p>{@code DISABLED} is a third kind of thing again (GW_VETTING_0029): not a conclusion the vetter
 * reached, but the record that an administrator switched the vetter off for this snapshot's
 * marketplace, so the chain skipped it. It is the only state that neither clears nor blocks —
 * {@link VettingChain} treats it as absent for the block decision but still requires positive
 * clearing evidence elsewhere in the run, so disabling every vetter leaves the run blocked.
 */
@Schema(description = "A vetter's conclusion about a snapshot")
public enum VerdictState {

    /** Nothing found. */
    PASS,

    /** Found something worth showing the reviewer that does not block approval. */
    WARN,

    /** Found something that blocks approval. */
    FAIL,

    /** The vetter did not produce a verdict: it threw, or it exceeded its time limit. */
    ERROR,

    /** The vetter was triggered and has not answered yet. Blocks, like every non-verdict. */
    PENDING,

    /**
     * An administrator disabled this vetter for the snapshot's marketplace (GW_VETTING_0029.2), so the
     * chain recorded this in its place instead of running it. Neither clears nor blocks.
     */
    DISABLED,

    /**
     * The chain had already stopped when it got here (GW_VETTING_0032.2): a vetter before this one
     * failed and the marketplace's chain mode is {@code stop-after-fail}, so this vetter was not
     * run.
     *
     * <p>Deliberately not {@link #DISABLED}. Both are absences rather than conclusions and both
     * neither clear nor block on their own, but one records a standing administrative decision and
     * the other records a run that ran out of road; they have different remedies, and the ledger
     * has to be able to tell them apart.
     *
     * <p>The consequence for the run is not on this state but on the effective outcome: a run
     * carrying any {@code NOT_REACHED} verdict is blocked whatever the waivers say (GW_VETTING_0032.3),
     * because the vetters it names never looked at the content. See {@link WaiverEvaluation}.
     */
    NOT_REACHED;

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
     * Whether this state blocks the chain. Everything that is not clearing blocks, with two
     * exceptions: {@link #DISABLED}, because an administrator switching a vetter off is a
     * deliberate, audited act rather than an unanswered or broken verdict and must not fail the
     * chain closed the way a timeout or a crash does (GW_VETTING_0029.3); and {@link #NOT_REACHED},
     * because a vetter the chain stopped short of did not object to anything either
     * (GW_VETTING_0032.2). Neither is a licence to clear: positive clearing evidence is still
     * required elsewhere in the run (see {@link VettingChain#aggregate}), and a run carrying a
     * {@code NOT_REACHED} verdict is blocked outright at the effective outcome, which is the one
     * that gates approval (see {@link WaiverEvaluation}).
     */
    public boolean blocking() {
        return this != DISABLED && this != NOT_REACHED && !clearing();
    }
}
