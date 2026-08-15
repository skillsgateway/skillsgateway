package io.github.jimisola.skillsgateway.vetting;

/**
 * The vetting SPI (GW_0037). A connector takes a quarantined, SHA-pinned snapshot and answers with
 * a {@link Verdict}; the gateway orchestrates, normalizes and records — it never vets itself
 * (ARCHITECTURE.md §4).
 *
 * <p>Implementations are ordinary Spring beans: declaring one adds it to the chain. They must be
 * side-effect free with respect to gateway state — the only thing a connector may do is read the
 * snapshot it was handed and return a verdict.
 *
 * <p>A connector is allowed to throw and allowed to be slow: {@link VettingService} records either
 * as an {@link VerdictState#ERROR} verdict, which blocks. It is never allowed to be skipped.
 */
public interface VettingConnector {

    /**
     * Stable identifier, persisted with every verdict and shown to reviewers. Renaming one breaks
     * the continuity of a snapshot's vetting history, so treat it as an API.
     */
    String name();

    /** Ascending chain position. Ties are broken by {@link #name()} so the order is total. */
    int order();

    /**
     * Identity of the rule set this connector currently carries, recorded on every run it takes
     * part in (GW_0049). It answers the question continuous re-vetting exists to raise: when a
     * snapshot that cleared last month is blocked today, was it the content that changed, or the
     * connector? Bump it whenever the rules change.
     *
     * <p>Default {@code "1"} so that adding a connector never requires thinking about versioning
     * before there is anything to version.
     */
    default String version() {
        return "1";
    }

    /** Reviewer-facing one-liner: what this connector looks for, and what it cannot see. */
    String description();

    /**
     * Examines the snapshot. Returning {@link VerdictState#PENDING} is legal and means "triggered,
     * answer later"; the chain treats it as blocking until a callback replaces it.
     */
    Verdict vet(SnapshotUnderVetting snapshot);
}
