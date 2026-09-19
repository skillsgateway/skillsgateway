package dev.skillsgateway.server.approval;

/**
 * An approval carried a reversal for a snapshot no administrator had withdrawn (GW_APPROVAL_0017).
 *
 * <p>Refused rather than ignored. A reversal that silently did nothing would let a caller believe
 * they had acknowledged a withdrawal that was never there — and, worse, would make the marker's
 * absence ambiguous: no marker would mean either "nothing was withdrawn" or "a reversal was
 * requested and quietly dropped".
 */
public class NothingToReverseException extends RuntimeException {

    public NothingToReverseException(long snapshotId) {
        super("snapshot %d has no administrative withdrawal to reverse".formatted(snapshotId));
    }
}
