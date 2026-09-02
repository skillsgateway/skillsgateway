package dev.skillsgateway.server.approval;

/**
 * An administrator asked to override a blocked vetting outcome without stating a reason (GW_0148).
 * The reason is what makes the override an act of taking responsibility rather than a silent
 * bypass, so it is required at the point the override is requested and its absence refuses the
 * approval before any state transition — nothing is decided and nothing is published.
 */
public class MissingOverrideReasonException extends RuntimeException {

    public MissingOverrideReasonException(long snapshotId) {
        super("overriding the blocked vetting outcome of snapshot %d requires a non-empty reason"
                .formatted(snapshotId));
    }
}
