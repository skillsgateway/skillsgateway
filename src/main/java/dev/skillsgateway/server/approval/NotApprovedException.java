package dev.skillsgateway.server.approval;

/**
 * A withdrawal named a snapshot that is not approved (GW_APPROVAL_0015): never approved, already
 * withdrawn, or withdrawn by a concurrent caller between the read and the transition.
 *
 * <p>The concurrent case is deliberately not an error condition of its own. The state transition
 * carries {@code state = 'approved'} in its own statement, so the loser of a race finds the work
 * already done — which is what makes a double submission harmless rather than a second unpublish.
 */
public class NotApprovedException extends RuntimeException {

    private final long snapshotId;

    private final String state;

    public NotApprovedException(long snapshotId, String state) {
        super("snapshot %d cannot be withdrawn because it is %s, not approved".formatted(snapshotId, state));
        this.snapshotId = snapshotId;
        this.state = state;
    }

    public long snapshotId() {
        return snapshotId;
    }

    public String state() {
        return state;
    }
}
