package dev.skillsgateway.server.approval;

/**
 * An administrator asked to withdraw an approved snapshot without stating a reason
 * (GW_APPROVAL_0015).
 *
 * <p>The reason is required rather than merely recorded when supplied, because it is the whole of
 * the accountability for this act: withdrawal takes one identity and no second reviewer, so what is
 * written down is all a later reader gets. It also has to survive into the refusal a future
 * approval of the same commit receives, where it is the only thing that tells a reviewer why.
 */
public class MissingRevocationReasonException extends RuntimeException {

    public MissingRevocationReasonException(long snapshotId) {
        super("withdrawing snapshot %d requires a non-empty reason".formatted(snapshotId));
    }
}
