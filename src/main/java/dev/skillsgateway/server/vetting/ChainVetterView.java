package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One vetter of a marketplace's effective vetting chain (GW_VETTING_0029.5): what it is, whether it
 * runs for this marketplace, and which setting decided that.
 *
 * @param reason the note recorded with the deciding setting, or null when a default decided it
 * @param updatedBy the administrator who last set the deciding setting, or null for a default
 */
@Schema(description = "A vetter in a marketplace's effective vetting chain")
public record ChainVetterView(
        @Schema(description = "Stable vetter name") String name,
        @Schema(description = "Position in the chain") int order,

        @Schema(description = "What the vetter looks for, and what it cannot see")
        String description,

        @Schema(description = "Identity of the rule set the vetter currently carries")
        String version,

        @Schema(description = "Whether the verdict is delegated to an operator-configured external service")
        boolean external,

        @Schema(description = "Whether the vetter runs for this marketplace")
        boolean enabled,

        @Schema(description = "Which setting decided the state")
        ChainSource source,

        @Schema(description = "The note recorded with the deciding setting, or null when it is the default")
        String reason,

        @Schema(description = "The administrator who last set the deciding setting, or null for the default")
        String updatedBy,

        @Schema(description = "When the deciding setting was last set, or null for the default")
        Instant updatedAt) {}
