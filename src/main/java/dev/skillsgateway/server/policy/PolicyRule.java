package dev.skillsgateway.server.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A stored policy deny rule (GW_0089); its expression compiled when the row was written. */
@Schema(description = "A CEL policy deny rule, evaluated fail-closed at approval time")
public record PolicyRule(
        @Schema(description = "Rule id") long id,

        @Schema(description = "Rule name: the identity a denial carries on the ledger and in the refusal")
        String name,

        @Schema(description = "What the rule prohibits, for reviewers reading a refusal")
        String description,

        @Schema(description = "CEL expression over the policy variables, compiled to a boolean at write time")
        String expression,

        @Schema(description = "Only enabled rules gate approvals; disabling is the audited off-switch")
        boolean enabled,

        @Schema(description = "Identity that created the rule")
        String createdBy,

        @Schema(description = "Creation time") Instant createdAt,

        @Schema(description = "Identity of the last update, or null")
        String updatedBy,

        @Schema(description = "Time of the last update, or null")
        Instant updatedAt) {}
