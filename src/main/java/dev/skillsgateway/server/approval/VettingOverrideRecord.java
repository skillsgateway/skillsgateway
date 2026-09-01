package dev.skillsgateway.server.approval;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The record that an administrator approved a snapshot over a blocked vetting outcome (GW_0142) —
 * the cockpit model's "captain disconnected the autopilot", captured as evidence about one
 * approval of a commit.
 *
 * <p>Its presence is what marks a served snapshot "approved over a vetting failure" so an override
 * is never indistinguishable from an approval the chain cleared on its own merits. The full history
 * of overrides lives on the append-only audit ledger; this is the current standing marker.
 *
 * @param reason the administrator's stated reason for taking responsibility for the block
 * @param blockingConnectors the connectors that were blocking at the moment of the override
 * @param uncoveredFindings a human-readable summary of the blocking findings no waiver covered
 */
@Schema(description = "An administrator's override of a blocked vetting outcome on a snapshot")
public record VettingOverrideRecord(
        @Schema(description = "Override id") long id,

        @Schema(description = "The snapshot approved over the block")
        long snapshotId,

        @Schema(description = "The administrator's stated reason")
        String reason,

        @Schema(description = "The connectors that were blocking, comma-separated")
        String blockingConnectors,

        @Schema(description = "Summary of the blocking findings no waiver covered")
        String uncoveredFindings,

        @Schema(description = "Identity of the administrator who overrode the block")
        String overriddenBy,

        @Schema(description = "When the override was recorded")
        Instant overriddenAt) {}
