package io.github.jimisola.skillsgateway.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A registered upstream marketplace")
public record Marketplace(
        long id,
        String name,
        String url,
        Instant createdAt,

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
        Instant lastSyncAt) {

    public static final String SYNC_ON_DEMAND = "on-demand";
    public static final String SYNC_SCHEDULED = "scheduled";
    public static final String SYNC_WEBHOOK = "webhook";
}
