package dev.skillsgateway.server.approval;

import java.util.List;

/**
 * Approval refused because the snapshot's recorded closure and the commit it pins do not agree
 * (GW_0164): a plugin the served manifest grafts that no closure member records, a member whose
 * tree is not what the commit holds at its path, a member the manifest does not declare, content
 * under the reserved directory that no member accounts for, or a member with no usable resolved
 * commit. Raised before any other gate and before the state transition, so nothing was decided
 * and nothing was published. There is no override for it.
 */
public class ClosureIncompleteException extends RuntimeException {

    private final long snapshotId;
    private final transient List<String> discrepancies;

    public ClosureIncompleteException(long snapshotId, List<String> discrepancies) {
        super("snapshot %d cannot be approved: its recorded closure does not describe the commit it pins — %s"
                .formatted(snapshotId, String.join("; ", discrepancies)));
        this.snapshotId = snapshotId;
        this.discrepancies = List.copyOf(discrepancies);
    }

    public long snapshotId() {
        return snapshotId;
    }

    /** Every discrepancy found, not only the first: the shape of the tampering is the evidence. */
    public List<String> discrepancies() {
        return discrepancies;
    }
}
