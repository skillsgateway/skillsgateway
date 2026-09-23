package dev.skillsgateway.server.approval;

/**
 * Approval refused because the snapshot's plugin names could not be read, so it cannot be shown not
 * to collide with the approved estate (GW_APPROVAL_0019). Deliberately not a collision: nobody found
 * one, and saying so would send a reviewer looking for an incumbent that does not exist.
 */
public class PluginInventoryUnavailableException extends RuntimeException {

    private final long snapshotId;

    public PluginInventoryUnavailableException(long snapshotId) {
        super(("snapshot %d cannot be approved: its plugin names could not be read from the pinned manifest, so it"
                        + " cannot be checked for a collision with the approved estate. This is usually a storage"
                        + " error; approving again retries the read.")
                .formatted(snapshotId));
        this.snapshotId = snapshotId;
    }

    public long snapshotId() {
        return snapshotId;
    }
}
