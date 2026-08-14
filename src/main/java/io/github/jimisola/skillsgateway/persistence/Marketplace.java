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
        Instant upstreamUpdatedAt) {}
