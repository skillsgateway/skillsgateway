package dev.skillsgateway.server.approval;

/**
 * A withdrawal asked the marketplace to return to its previous approved snapshot, and there is not
 * one (GW_APPROVAL_0016).
 *
 * <p>Raised before anything is withdrawn. Withdrawing first and discovering afterwards that there
 * is nothing to return to would leave the marketplace serving nothing — which is the other outcome
 * the caller could have chosen and explicitly did not, so it must not be arrived at by accident.
 */
public class RollbackUnavailableException extends RuntimeException {

    private final long snapshotId;

    private final String marketplace;

    public RollbackUnavailableException(long snapshotId, String marketplace) {
        super("snapshot %d cannot be withdrawn with a return to the previous approved snapshot:"
                + " marketplace %s has no other approved snapshot to serve".formatted(snapshotId, marketplace));
        this.snapshotId = snapshotId;
        this.marketplace = marketplace;
    }

    public long snapshotId() {
        return snapshotId;
    }

    public String marketplace() {
        return marketplace;
    }
}
