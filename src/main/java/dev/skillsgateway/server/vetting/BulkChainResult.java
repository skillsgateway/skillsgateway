package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What one estate-wide chain change did, marketplace by marketplace (GW_VETTING_0037).
 *
 * <p>The counts are a summary of {@code results}, never a substitute for it: a caller that reads
 * only {@code applied} would read a partial failure as a success, which is the one thing this shape
 * exists to prevent. The transport says the same thing — 200 only when nothing failed.
 */
@Schema(description = "The outcome of one estate-wide vetting-chain change")
public record BulkChainResult(
        @Schema(description = "Identifier shared by every ledger entry this request caused")
        String correlationId,

        @Schema(description = "How many marketplaces changed")
        int applied,

        @Schema(description = "How many already held the requested state, so nothing was written")
        int unchanged,

        @Schema(description = "How many were refused") int failed,

        @Schema(description = "The outcome per marketplace, in the order the request named them")
        List<Outcome> results) {

    public BulkChainResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** Whether every marketplace in the request was applied or already in the requested state. */
    public boolean whollySucceeded() {
        return failed == 0;
    }

    /** What happened to one marketplace. */
    @Schema(description = "What an estate-wide change did to one marketplace")
    public record Outcome(
            @Schema(description = "The marketplace as the request named it")
            String marketplace,

            @Schema(description = "applied, unchanged, or failed")
            Status status,

            @Schema(description = "What changed, or why it was refused")
            String detail) {}

    /** The three things that can happen to one marketplace. */
    @Schema(description = "The outcome for one marketplace of an estate-wide change")
    public enum Status {
        /** The marketplace changed, and the change is on the ledger. */
        APPLIED,

        /** Nothing to do — there was no override to clear — so nothing was written or recorded. */
        UNCHANGED,

        /** The marketplace was refused; nothing was written for it. */
        FAILED
    }
}
