package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "An immutable, SHA-identified snapshot of an upstream marketplace")
public record Snapshot(
        @Schema(description = "Snapshot id") long id,
        @Schema(description = "Owning marketplace id") long marketplaceId,

        @Schema(description = "Upstream commit SHA the snapshot is pinned to")
        String sha,

        @Schema(
                description = "held, approved, rejected, or revoked (retroactively quarantined by re-vetting)",
                allowableValues = {"held", "approved", "rejected", "revoked"})
        String state,

        @Schema(description = "Policy or vetting violation that rejected or revoked the snapshot, or null")
        String violation,

        @Schema(description = "Ingestion time") Instant createdAt,

        @Schema(description = "Reviewer who decided, or null while held")
        String decidedBy,

        @Schema(description = "Decision time, or null while held")
        Instant decidedAt,

        @Schema(description = "When re-vetting revoked the snapshot, or null")
        Instant revokedAt,

        @Schema(description = "Identity that revoked it, or null")
        String revokedBy,

        @Schema(description = "When the snapshot was soft-deleted, or null when it is live")
        Instant deletedAt,

        @Schema(description = "Retention criterion or administrative reason for the deletion")
        String deletedReason,

        @Schema(description = "End of the restore window; after it compaction removes the snapshot permanently")
        Instant purgeAfter) {

    public static final String HELD = "held";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    /**
     * Retroactively quarantined (GW_0050): the snapshot was approved and published, and a later
     * re-vetting run found a violation its active waivers do not cover. Its content is no longer
     * served, and it cannot be served again without a fresh approve decision.
     */
    public static final String REVOKED = "revoked";

    /** Soft-deleted: marked for removal but still restorable until {@link #purgeAfter()}. */
    public boolean deleted() {
        return deletedAt != null;
    }

    /** Whether the snapshot is in a state a reviewer may decide from ({@code held} or {@code revoked}). */
    public boolean decidable() {
        return HELD.equals(state) || REVOKED.equals(state);
    }
}
