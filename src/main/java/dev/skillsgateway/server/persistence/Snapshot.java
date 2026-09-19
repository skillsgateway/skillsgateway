package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "An immutable, SHA-identified snapshot of an upstream marketplace")
public record Snapshot(
        @Schema(description = "Snapshot id") long id,
        @Schema(description = "Owning marketplace id") long marketplaceId,

        @Schema(
                description = "Commit SHA the snapshot is pinned to and serves: the upstream commit, or the"
                        + " synthesised composite when external plugin sources were resolved")
        String sha,

        @Schema(description = "Commit SHA ingested from upstream; equal to sha unless a composite was synthesised")
        String upstreamSha,

        @Schema(
                description = "held, approved, rejected, or revoked (retroactively quarantined by re-vetting)",
                allowableValues = {"held", "approved", "rejected", "revoked"})
        String state,

        @Schema(description = "Policy or vetting violation that rejected or revoked the snapshot, or null")
        String violation,

        @Schema(description = "Ingestion time") Instant createdAt,

        @Schema(
                description = "Identity that triggered the ingestion: a principal for an on-demand ingest or a"
                        + " push, 'scheduler' or 'webhook' for an automated trigger, null for a snapshot"
                        + " ingested before the actor was recorded")
        String ingestedBy,

        @Schema(description = "Reviewer who decided, or null while held")
        String decidedBy,

        @Schema(description = "Decision time, or null while held")
        Instant decidedAt,

        @Schema(description = "When re-vetting revoked the snapshot, or null")
        Instant revokedAt,

        @Schema(description = "Identity that revoked it, or null")
        String revokedBy,
        @Schema(
                description = "Whether a re-vetting run or an administrator revoked it, or null if it never was."
                        + " The two lift differently: a re-vetting revocation lifts when its finding is cleared,"
                        + " an administrative one only when a second administrator reverses it",
                allowableValues = {"revet", "administrative"})
        String revokedKind,

        @Schema(description = "When the snapshot was soft-deleted, or null when it is live")
        Instant deletedAt,

        @Schema(description = "Retention criterion or administrative reason for the deletion")
        String deletedReason,

        @Schema(description = "End of the restore window; after it compaction removes the snapshot permanently")
        Instant purgeAfter) {

    /** A re-vetting run revoked it: lifted by clearing the finding (GW_VETTING_0013). */
    public static final String REVOKED_BY_REVET = "revet";

    /**
     * An administrator revoked it on knowledge the vetting chain does not hold (GW_APPROVAL_0015).
     * There is no finding to clear — the chain already clears the content — so the only thing that
     * lifts one is a reversal by a different administrator (GW_APPROVAL_0017).
     */
    public static final String REVOKED_ADMINISTRATIVELY = "administrative";

    public static final String HELD = "held";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    /**
     * Retroactively quarantined (GW_VETTING_0013): the snapshot was approved and published, and a later
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
