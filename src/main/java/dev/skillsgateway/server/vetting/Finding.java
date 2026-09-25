package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One thing a vetter found.
 *
 * @param id stable rule identifier ({@code aws-access-key-id}), never an ordinal — it is the
 *     identity a scoped waiver will later be written against, so it must survive reordering and
 *     renaming of everything around it
 * @param severity how much it matters; {@link Severity#INFO} is recorded without affecting the
 *     verdict
 * @param location where in the snapshot it was found, normally {@code path:line}
 * @param message what a reviewer needs to read, never containing the matched secret itself
 * @param content the git blob id of the file the finding is located in, stamped by the gateway
 *     from the pinned tree after the vetter answers (GW_VETTING_0041) — never taken from a vetter —
 *     or {@code null} when the location names no file of the snapshot
 */
@Schema(description = "One thing a vetter found in a snapshot")
public record Finding(
        @Schema(description = "Stable rule identifier, e.g. aws-access-key-id", example = "aws-access-key-id")
        String id,

        @Schema(description = "How much the finding matters")
        Severity severity,

        @Schema(description = "Where in the snapshot it was found, normally path:line")
        String location,

        @Schema(description = "Reviewer-facing explanation; never echoes the matched secret")
        String message,

        @Schema(
                description = "Git blob id of the file the finding is in, set by the gateway from the pinned tree;"
                        + " null when the location names no file. Findings on identical content share it.",
                example = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")
        String content) {

    /** A finding as a vetter reports it: the gateway, not the vetter, identifies the content. */
    public Finding(String id, Severity severity, String location, String message) {
        this(id, severity, location, message, null);
    }

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

    /** This finding with its content identity set, or cleared when {@code blob} is null. */
    public Finding withContent(String blob) {
        return new Finding(id, severity, location, message, blob);
    }

    /**
     * The line number in {@link #location()}, or {@code null} when the location carries none —
     * the same reading of {@code path:line} that {@link WaiverScope#pathOf} makes.
     */
    public Integer line() {
        String path = WaiverScope.pathOf(location);
        if (location == null || path.length() == location.length()) {
            return null;
        }
        try {
            return Integer.valueOf(location.substring(path.length() + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
