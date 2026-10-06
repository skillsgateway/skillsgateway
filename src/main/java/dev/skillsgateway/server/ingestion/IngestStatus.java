package dev.skillsgateway.server.ingestion;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
        description = "A marketplace's ingest in progress, if any, and how the last one ended (GW_INGEST_0068)."
                + " Both can be set at once: an ingest is running and the previous one has ended.")
public record IngestStatus(
        String marketplace,

        @Schema(description = "The ingest in progress, whatever triggered it; null when none is running")
        Running running,

        @Schema(description = "How the last finished ingest ended; null before the first")
        Last last) {

    @Schema(description = "An ingest in progress")
    public record Running(
            @Schema(
                    description = "The stage it has reached",
                    allowableValues = {"queued", "fetching", "evaluating-manifest", "vetting"})
            String stage,

            @Schema(description = "When it was requested or, for an automated trigger, started")
            Instant startedAt,

            @Schema(
                    description = "True when the gateway instance running it stopped renewing it (GW_INGEST_0069):"
                            + " it will not finish, and a new ingest may be started in its place")
            boolean interrupted) {}

    @Schema(description = "The last finished ingest: the record kept on the marketplace (GW_INGEST_0039)")
    public record Last(
            @Schema(description = "When it ended") Instant at,

            @Schema(allowableValues = {"succeeded", "failed"})
            String outcome,

            @Schema(description = "The snapshot it recorded; null for a failure, or once that snapshot is purged")
            Long snapshotId,

            @Schema(
                    description = "That snapshot's current state: held, or rejected by the manifest policy, until"
                            + " a reviewer decides it")
            String snapshotState,

            @Schema(description = "Why it failed, readable on its own (GW_INGEST_0038); null unless it failed")
            String reason,

            @Schema(description = "The same failure's parts, for a client to act on; null unless it failed")
            UpstreamFailure failure) {}
}
