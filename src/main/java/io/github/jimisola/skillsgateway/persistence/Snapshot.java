package io.github.jimisola.skillsgateway.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "An immutable, SHA-identified snapshot of an upstream marketplace")
public record Snapshot(
        @Schema(description = "Snapshot id") long id,
        @Schema(description = "Owning marketplace id") long marketplaceId,

        @Schema(description = "Upstream commit SHA the snapshot is pinned to")
        String sha,

        @Schema(
                description = "held, approved, or rejected",
                allowableValues = {"held", "approved", "rejected"})
        String state,

        @Schema(description = "Policy violation that rejected the snapshot, or null")
        String violation,

        @Schema(description = "Ingestion time") Instant createdAt,

        @Schema(description = "Reviewer who decided, or null while held")
        String decidedBy,

        @Schema(description = "Decision time, or null while held")
        Instant decidedAt,

        @Schema(description = "When the snapshot was soft-deleted, or null when it is live")
        Instant deletedAt,

        @Schema(description = "Retention criterion or administrative reason for the deletion")
        String deletedReason,

        @Schema(description = "End of the restore window; after it compaction removes the snapshot permanently")
        Instant purgeAfter) {

    public static final String HELD = "held";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    /** Soft-deleted: marked for removal but still restorable until {@link #purgeAfter()}. */
    public boolean deleted() {
        return deletedAt != null;
    }
}
