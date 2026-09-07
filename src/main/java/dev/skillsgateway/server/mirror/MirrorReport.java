package dev.skillsgateway.server.mirror;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * What the mirror holds against what the facade serves (GW_0172).
 *
 * <p>The comparison is made against published storage, not against what the gateway last tried to
 * push. A report derived from push history would answer a different question — "did my last push
 * report success" — and would agree with itself even after somebody changed the mirror underneath
 * the gateway, which is one of the two ways a mirror actually drifts.
 *
 * @param enabled whether the mirror is configured at all; everything below is null or empty when
 *     it is not
 * @param marketplace the single mirrored marketplace
 * @param url the mirror's clone URL; never carries a credential, because one is refused at startup
 * @param reachable whether the gateway could read the mirror's references for this report
 * @param inSync whether the mirror holds exactly the references the facade serves. False whenever
 *     the mirror could not be reached: silence must not read as agreement
 * @param servedTip the commit {@code refs/heads/main} resolves to in published storage, or null
 *     when the marketplace serves nothing
 * @param mirrorTip the commit {@code refs/heads/main} resolves to on the mirror, or null
 * @param missingOnMirror served references the mirror lacks or holds at a different commit
 * @param staleOnMirror references the mirror still holds that are no longer served — what a
 *     revocation that did not reach the mirror leaves behind
 * @param pendingUpdates mirror updates queued or in flight right now
 * @param lastAttemptAt when the mirror was last updated, or attempted
 * @param lastAttemptOutcome {@code ok}, {@code failed}, or {@code none} if it has never been tried
 * @param error why the comparison or the last attempt failed, or null
 */
@Schema(description = "How the read-only forge mirror compares to what the facade serves")
public record MirrorReport(
        @Schema(description = "Whether a mirror is configured")
        boolean enabled,

        @Schema(description = "The mirrored marketplace") String marketplace,

        @Schema(description = "Mirror clone URL; never carries a credential")
        String url,

        @Schema(description = "Whether the mirror could be read for this report")
        boolean reachable,

        @Schema(
                description = "Whether the mirror holds exactly the served references;"
                        + " false whenever the mirror could not be read")
        boolean inSync,

        @Schema(description = "Commit refs/heads/main resolves to in published storage")
        String servedTip,

        @Schema(description = "Commit refs/heads/main resolves to on the mirror")
        String mirrorTip,

        @Schema(description = "Served references the mirror lacks or holds at another commit")
        List<String> missingOnMirror,

        @Schema(description = "References the mirror still holds that are no longer served")
        List<String> staleOnMirror,

        @Schema(description = "Mirror updates queued or in flight")
        int pendingUpdates,

        @Schema(description = "When the mirror was last updated or attempted")
        Instant lastAttemptAt,

        @Schema(
                description = "Outcome of the last update attempt",
                allowableValues = {"ok", "failed", "none"})
        String lastAttemptOutcome,

        @Schema(description = "Why the comparison or the last attempt failed")
        String error) {

    /** Outcome of an attempt that succeeded. */
    public static final String OK = "ok";

    /** Outcome of an attempt that exhausted its retries; the mirror is drifted until the next one. */
    public static final String FAILED = "failed";

    /** No attempt has been made since this gateway started. */
    public static final String NONE = "none";

    public MirrorReport {
        missingOnMirror = missingOnMirror == null ? List.of() : List.copyOf(missingOnMirror);
        staleOnMirror = staleOnMirror == null ? List.of() : List.copyOf(staleOnMirror);
    }

    /** The shipped default: nothing configured, nothing pushed, nothing to compare. */
    public static MirrorReport disabled() {
        return new MirrorReport(false, null, null, false, false, null, null, List.of(), List.of(), 0, null, NONE, null);
    }
}
