package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A registered marketplace, fetched from an upstream")
public record Marketplace(
        long id,
        String name,

        @Schema(description = "Upstream clone URL") String url,

        Instant createdAt,

        @Schema(
                description = "Identity that registered the marketplace, or null for one registered before the"
                        + " registrant was recorded")
        String registeredBy,

        @Schema(description = "Detected forge (github, gitlab, bitbucket, azure-devops, gitea) or null")
        String forge,

        @Schema(description = "Project path on the forge") String forgeProject,

        @Schema(description = "Project description from the forge")
        String description,

        @Schema(description = "Last upstream update as reported by the forge")
        Instant upstreamUpdatedAt,

        @Schema(
                description = "How upstream content reaches quarantine: on-demand, scheduled, or webhook."
                        + " The trigger only — every mode lands snapshots held behind the approval gate.",
                allowableValues = {"on-demand", "scheduled", "webhook"})
        String syncMode,

        @Schema(description = "Last sync attempt (success or failure), or null before the first one")
        Instant lastSyncAt,

        @Schema(description = "When the last ingest attempt ended, whatever triggered it; null before the first")
        Instant lastIngestAt,

        @Schema(
                description = "How the last ingest attempt ended (GW_INGEST_0039); null before the first",
                allowableValues = {"succeeded", "failed"})
        String lastIngestOutcome,

        @Schema(
                description = "Why the last ingest attempt failed: reason, root cause and next step"
                        + " (GW_INGEST_0038); null unless it failed")
        String lastIngestReason,

        @Schema(
                description = "The snapshot the last successful ingest recorded (GW_INGEST_0068); null when it"
                        + " failed, before the first, or once that snapshot has been purged")
        Long lastIngestSnapshotId) {

    public static final String INGEST_SUCCEEDED = "succeeded";
    public static final String INGEST_FAILED = "failed";

    /**
     * The one history a marketplace has: what the facade serves. Its single-lineage guarantee
     * (GW_INGEST_0006) is what makes a snapshot's provenance a straight line rather than a choice of
     * branches.
     */
    public static final String LINEAGE_REF = "refs/heads/main";

    public static final String SYNC_ON_DEMAND = "on-demand";
    public static final String SYNC_SCHEDULED = "scheduled";
    public static final String SYNC_WEBHOOK = "webhook";
}
