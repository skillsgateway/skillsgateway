package dev.skillsgateway.server.approval;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A separation-of-duties refusal (GW_0096): the reviewer is on the supply side of the very
 * snapshot they are approving.
 *
 * <p>Raised only in {@code enforce} mode. In {@code warn} mode the same conflicts are computed and
 * recorded on the audit ledger, and the approval proceeds — the difference between the two modes
 * is this exception and nothing else, so the detection cannot drift between them.
 */
public class FourEyesConflictException extends RuntimeException {

    /**
     * One reason the reviewer is not independent of the content.
     *
     * @param role which supply-side act they performed: {@code ingested-by}, {@code registered-by}
     *     or {@code waiver-author}
     * @param principal the identity that performed it — the reviewer's own, by definition
     * @param waiverId the waiver they authored, for {@code waiver-author}; null otherwise
     */
    @Schema(description = "A supply-side act by the reviewer that makes this approval a self-approval")
    public record Conflict(
            @Schema(
                    description = "The supply-side act",
                    allowableValues = {"ingested-by", "registered-by", "waiver-author"})
            String role,

            @Schema(description = "The identity that performed it")
            String principal,

            @Schema(description = "The waiver the reviewer authored, for a waiver-author conflict")
            Long waiverId) {}

    private final transient List<Conflict> conflicts;

    public FourEyesConflictException(long snapshotId, List<Conflict> conflicts) {
        super("four-eyes rule refused approval of snapshot %d: the reviewer is %s"
                .formatted(
                        snapshotId,
                        conflicts.stream().map(Conflict::role).distinct().toList()));
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }
}
