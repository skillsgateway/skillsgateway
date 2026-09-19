package dev.skillsgateway.server.approval;

/**
 * An administrator asked to reverse a withdrawal without stating a reason (GW_APPROVAL_0017).
 *
 * <p>Required for the same reason the withdrawal's own reason is: the marker this writes is what a
 * later reader sees when they ask why content that was once withdrawn is being served again, and an
 * empty answer there is worse than none, because it looks like a record was kept.
 */
public class MissingReversalReasonException extends RuntimeException {

    public MissingReversalReasonException(long snapshotId) {
        super("reversing the withdrawal of snapshot %d requires a non-empty reason".formatted(snapshotId));
    }
}
