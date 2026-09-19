package dev.skillsgateway.server.approval;

/**
 * The administrator who withdrew a snapshot tried to reverse their own withdrawal
 * (GW_APPROVAL_0017).
 *
 * <p>Withdrawing takes one identity because it only ever removes content from the wire. Reversing
 * publishes, so it takes a second — otherwise one administrator could withdraw and restore at will,
 * and the withdrawal would be a note to self rather than a decision the organisation made.
 *
 * <p>Refused unconditionally, not under a configurable mode, and the same rule is carried as a
 * database constraint on the marker row so no future call site can lose it.
 */
public class SelfReversalException extends RuntimeException {

    private final long snapshotId;

    private final String administrator;

    public SelfReversalException(long snapshotId, String administrator) {
        super("%s withdrew snapshot %d and cannot reverse it; a different administrator must"
                .formatted(administrator, snapshotId));
        this.snapshotId = snapshotId;
        this.administrator = administrator;
    }

    public long snapshotId() {
        return snapshotId;
    }

    public String administrator() {
        return administrator;
    }
}
