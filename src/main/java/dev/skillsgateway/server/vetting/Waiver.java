package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A scoped, expiring accepted-risk exception against one vetting rule (GW_0044).
 *
 * <p>Every field that makes the acceptance reviewable is mandatory at construction, not merely at
 * the REST edge: a waiver with no justification, no approver, or no expiry cannot be built, so no
 * code path can produce one. There is no representation of an unlimited waiver in this type.
 *
 * @param ruleId the {@link Finding#id()} being accepted
 * @param scope how {@code scopeValue} is matched against a finding
 * @param scopeValue a commit SHA or a repository-relative path, per {@code scope}
 * @param justification why this risk is accepted; free text, and the reason a reviewer can be
 *     held to
 * @param approvedBy the acting principal at the moment of creation
 * @param expiresAt when the acceptance lapses; never null, and rejected when not in the future
 * @param revokedAt when a reviewer withdrew it, or null
 * @param expiredRecordedAt when the sweep first noted the expiry in the ledger; never consulted
 *     by the gate
 */
@Schema(description = "A scoped, expiring accepted-risk exception against one vetting rule")
public record Waiver(
        @Schema(description = "Waiver id") long id,

        @Schema(description = "Marketplace the waiver belongs to")
        long marketplaceId,

        @Schema(description = "Marketplace name") String marketplace,

        @Schema(description = "Finding rule identifier being accepted", example = "aws-access-key-id")
        String ruleId,

        @Schema(description = "How the scope value is matched")
        WaiverScope scope,

        @Schema(description = "A commit SHA for snapshot scope, a repository-relative path for path scope")
        String scopeValue,

        @Schema(description = "Why the risk is accepted") String justification,

        @Schema(description = "Identity that accepted the risk")
        String approvedBy,

        @Schema(description = "When the waiver was created") Instant createdAt,

        @Schema(description = "When the acceptance lapses; never null")
        Instant expiresAt,

        @Schema(description = "When the waiver was revoked, or null")
        Instant revokedAt,

        @Schema(description = "Identity that revoked it, or null")
        String revokedBy,

        @Schema(description = "When the expiry sweep first recorded the expiry in the ledger, or null")
        Instant expiredRecordedAt) {

    public Waiver {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("waiver rule id is required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("waiver scope is required");
        }
        if (scopeValue == null || scopeValue.isBlank()) {
            throw new IllegalArgumentException("waiver scope value is required");
        }
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("waiver justification is required");
        }
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("waiver approver is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("waiver expiry is required; unlimited waivers do not exist");
        }
    }

    /**
     * Whether this waiver suppresses anything at {@code now} (GW_0046). Revocation and expiry are
     * the same answer — "not active" — because the gate does not care why an acceptance stopped
     * applying, only that it did.
     */
    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Whether this waiver covers {@code finding} in a snapshot pinned to {@code sha}, at
     * {@code now}. Every conjunct must hold: an inactive waiver, a different rule, or a scope
     * that does not name this content all answer no.
     */
    public boolean covers(Finding finding, String sha, Instant now) {
        return active(now)
                && finding != null
                && ruleId.equals(finding.id())
                && scope.matches(scopeValue, sha, WaiverScope.pathOf(finding.location()));
    }
}
