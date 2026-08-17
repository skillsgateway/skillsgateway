package io.github.jimisola.skillsgateway.roles;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One current role grant; revocation deletes the row and the audit ledger keeps the history. */
@Schema(description = "A role grant")
public record RoleGrant(
        @Schema(description = "Grant id") long id,

        @Schema(description = "Principal the role is granted to")
        String principal,

        @Schema(
                description = "The role",
                allowableValues = {"admin", "approver", "auditor"})
        String role,

        @Schema(description = "Marketplace an approver grant is scoped to; null for the global roles")
        String marketplace,

        @Schema(description = "Identity that made the grant")
        String grantedBy,

        @Schema(description = "When the grant was made") Instant grantedAt) {

    public static final String ADMIN = "admin";
    public static final String APPROVER = "approver";
    public static final String AUDITOR = "auditor";
}
