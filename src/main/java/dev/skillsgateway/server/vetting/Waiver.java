package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

/**
 * A scoped, expiring accepted-risk exception against one vetting rule (GW_VETTING_0007).
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
 * @param content for a group waiver (GW_VETTING_0042), the git blob id the accepted findings are in;
 *     {@code null} for a waiver on the rule alone
 * @param line for a group waiver, the line within that blob, or {@code null} when the group's
 *     findings carry none
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
        Instant expiredRecordedAt,

        @Schema(description = "For a group waiver, the git blob id of the accepted findings; null otherwise")
        String content,

        @Schema(description = "For a group waiver, the line within that blob; null when the group has none")
        Integer line) {

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
        if (content != null && scope != WaiverScope.SNAPSHOT) {
            throw new IllegalArgumentException("a group waiver is scoped to one snapshot");
        }
        if (content != null && !BLOB_ID.matcher(content).matches()) {
            throw new IllegalArgumentException("a group waiver's content must be a git blob id");
        }
        if (line != null && (content == null || line < 1)) {
            throw new IllegalArgumentException("a group waiver's line must be positive and name content");
        }
    }

    /** A git object id as the gateway writes one: SHA-1 or SHA-256, lower-case hex. */
    static final java.util.regex.Pattern BLOB_ID = java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** A waiver on the rule alone, with no content qualifier. */
    public Waiver(
            long id,
            long marketplaceId,
            String marketplace,
            String ruleId,
            WaiverScope scope,
            String scopeValue,
            String justification,
            String approvedBy,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            String revokedBy,
            Instant expiredRecordedAt) {
        this(
                id,
                marketplaceId,
                marketplace,
                ruleId,
                scope,
                scopeValue,
                justification,
                approvedBy,
                createdAt,
                expiresAt,
                revokedAt,
                revokedBy,
                expiredRecordedAt,
                null,
                null);
    }

    /**
     * Whether this waiver suppresses anything at {@code now} (GW_VETTING_0009). Revocation and expiry are
     * the same answer — "not active" — because the gate does not care why an acceptance stopped
     * applying, only that it did.
     */
    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Whether this waiver covers {@code finding} in a snapshot pinned to {@code sha}, at
     * {@code now}. Every conjunct must hold: an inactive waiver, a different rule, a scope that
     * does not name this content, or — for a group waiver — a different blob or line all answer no.
     */
    @Requirements({"GW_VETTING_0042"})
    public boolean covers(Finding finding, String sha, Instant now) {
        return active(now)
                && finding != null
                && ruleId.equals(finding.id())
                && scope.matches(scopeValue, sha, WaiverScope.pathOf(finding.location()))
                && coversContent(finding);
    }

    /**
     * The group half (GW_VETTING_0042): a waiver with no content qualifier places no constraint here,
     * and one with it covers only a finding on that very blob and line. A finding the gateway could
     * not tie to a blob is never covered by a group waiver.
     */
    private boolean coversContent(Finding finding) {
        return content == null || (content.equals(finding.content()) && Objects.equals(line, finding.line()));
    }
}
