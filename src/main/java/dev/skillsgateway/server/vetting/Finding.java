package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One thing a connector found.
 *
 * @param id stable rule identifier ({@code aws-access-key-id}), never an ordinal — it is the
 *     identity a scoped waiver will later be written against, so it must survive reordering and
 *     renaming of everything around it
 * @param severity how much it matters; {@link Severity#INFO} is recorded without affecting the
 *     verdict
 * @param location where in the snapshot it was found, normally {@code path:line}
 * @param message what a reviewer needs to read, never containing the matched secret itself
 */
@Schema(description = "One thing a vetting connector found in a snapshot")
public record Finding(
        @Schema(description = "Stable rule identifier, e.g. aws-access-key-id", example = "aws-access-key-id")
        String id,

        @Schema(description = "How much the finding matters")
        Severity severity,

        @Schema(description = "Where in the snapshot it was found, normally path:line")
        String location,

        @Schema(description = "Reviewer-facing explanation; never echoes the matched secret")
        String message) {

    public Finding {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("finding id is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("finding severity is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("finding message is required");
        }
    }
}
