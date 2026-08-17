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
    PENDING;

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
}
