package dev.skillsgateway.server.approval;

import java.time.Instant;

/**
 * An ordinary approval named a commit an administrator had withdrawn (GW_APPROVAL_0017).
 *
 * <p>Carries the withdrawal's own details because the refusal must name them. A reviewer who meets
 * an unexplained refusal reasonably concludes they have hit a defect and looks for a way around it;
 * one who is told what was withdrawn, by whom and why can judge whether anything has changed — and
 * if it has, ask a second administrator to reverse it.
 */
public class SnapshotWithdrawnException extends RuntimeException {

    private final long snapshotId;

    private final String reason;

    private final String revokedBy;

    private final Instant revokedAt;

    public SnapshotWithdrawnException(long snapshotId, String reason, String revokedBy, Instant revokedAt) {
        super("snapshot %d cannot be approved: %s withdrew this commit at %s (%s)."
                + " Reversing a withdrawal takes an administrator other than the one who made it"
                        .formatted(snapshotId, revokedBy, revokedAt, reason));
        this.snapshotId = snapshotId;
        this.reason = reason;
        this.revokedBy = revokedBy;
        this.revokedAt = revokedAt;
    }

    public long snapshotId() {
        return snapshotId;
    }

    public String reason() {
        return reason;
    }

    public String revokedBy() {
        return revokedBy;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
