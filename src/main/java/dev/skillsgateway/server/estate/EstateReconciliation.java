package dev.skillsgateway.server.estate;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * The outcome of one reconciliation run (GW_0083, GW_0087): per declared entry, what happened and
 * why. Secret values never appear here — a failed receiver entry names the entry and the rule it
 * broke, never the material it carried.
 */
@Schema(description = "The outcome of one estate reconciliation run")
public record EstateReconciliation(
        @Schema(description = "When the run happened") Instant ranAt,

        @Schema(
                description = "What started the run",
                allowableValues = {"startup", "api"})
        String trigger,

        @Schema(description = "Per declared entry, what happened")
        List<Entry> entries,

        @Schema(description = "Entries created") int created,

        @Schema(description = "Entries updated to match the declaration")
        int updated,

        @Schema(description = "Entries already converged; nothing was written")
        int unchanged,

        @Schema(description = "Entries that failed validation and were skipped")
        int failed) {

    /** One declared entry's outcome. */
    @Schema(description = "One declared entry's reconciliation outcome")
    public record Entry(
            @Schema(
                    description = "The kind of declared object",
                    allowableValues = {"marketplace", "grant", "webhook", "audit-sink"})
            String kind,

            @Schema(description = "The declared name (for a grant: principal/role/marketplace)")
            String name,

            @Schema(
                    description = "What the reconciler did",
                    allowableValues = {"created", "updated", "unchanged", "failed"})
            String action,

            @Schema(description = "What changed, or why the entry failed; never a secret value")
            String detail) {

        static Entry created(String kind, String name, String detail) {
            return new Entry(kind, name, "created", detail);
        }

        static Entry updated(String kind, String name, String detail) {
            return new Entry(kind, name, "updated", detail);
        }

        static Entry unchanged(String kind, String name) {
            return new Entry(kind, name, "unchanged", null);
        }

        static Entry failed(String kind, String name, String reason) {
            return new Entry(kind, name, "failed", reason);
        }
    }

    static EstateReconciliation of(String trigger, List<Entry> entries) {
        return new EstateReconciliation(
                Instant.now(),
                trigger,
                List.copyOf(entries),
                count(entries, "created"),
                count(entries, "updated"),
                count(entries, "unchanged"),
                count(entries, "failed"));
    }

    private static int count(List<Entry> entries, String action) {
        return (int)
                entries.stream().filter(entry -> action.equals(entry.action())).count();
    }
}
