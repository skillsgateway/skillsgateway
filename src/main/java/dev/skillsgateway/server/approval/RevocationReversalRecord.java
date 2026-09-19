package dev.skillsgateway.server.approval;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The standing marker that a snapshot is served over an administrative revocation somebody reversed
 * (GW_APPROVAL_0017).
 *
 * <p>It carries the revocation's own details rather than pointing at them, because the approval that
 * reverses a revocation clears {@code revoked_at}, {@code revoked_by} and {@code revoked_kind} on
 * its way to approved. Without the copy there would be nothing left to read, and content served
 * over a reversed withdrawal would be indistinguishable from content nobody ever withdrew.
 *
 * @param revocationReason what the administrator who withdrew it said
 * @param revokedBy who withdrew it
 * @param revokedAt when they withdrew it
 * @param reason the reversing administrator's stated reason
 * @param reversedBy who reversed it — never {@code revokedBy}, enforced by the service and again by
 *     a database constraint, so no future call site can let one administrator undo their own
 *     withdrawal alone
 * @param reversedAt when they reversed it
 */
@Schema(description = "A snapshot served over an administrative revocation that was reversed")
public record RevocationReversalRecord(
        long id,
        long snapshotId,

        @Schema(description = "The stated reason the snapshot was withdrawn")
        String revocationReason,

        @Schema(description = "The administrator who withdrew it")
        String revokedBy,

        @Schema(description = "When it was withdrawn") Instant revokedAt,

        @Schema(description = "The stated reason it was put back")
        String reason,

        @Schema(description = "The administrator who put it back; never the one who withdrew it")
        String reversedBy,

        @Schema(description = "When it was put back") Instant reversedAt) {}
