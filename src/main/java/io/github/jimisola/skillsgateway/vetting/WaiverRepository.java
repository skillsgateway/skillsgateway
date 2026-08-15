package io.github.jimisola.skillsgateway.vetting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of waivers (GW_0044). Rows are only ever inserted, revoked, or stamped by the expiry
 * sweep — a waiver's rule, scope, justification, approver and expiry are immutable once written, so
 * that "what was accepted" cannot be edited after the fact into something the approver never
 * agreed to. Changing an acceptance means revoking it and writing a new one, which leaves both in
 * the ledger.
 */
@Repository
public class WaiverRepository {

    private static final String COLUMNS =
            "w.id, w.marketplace_id, m.name AS marketplace, w.rule_id, w.scope_kind, w.scope_value,"
                    + " w.justification, w.approved_by, w.created_at, w.expires_at, w.revoked_at, w.revoked_by,"
                    + " w.expired_recorded_at";

    private final JdbcClient jdbc;

    public WaiverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Waiver create(
            long marketplaceId,
            String ruleId,
            WaiverScope scope,
            String scopeValue,
            String justification,
            String approvedBy,
            Instant expiresAt) {
        long id = jdbc.sql("INSERT INTO vetting_waivers (marketplace_id, rule_id, scope_kind, scope_value,"
                        + " justification, approved_by, created_at, expires_at) VALUES (:marketplaceId, :ruleId,"
                        + " :scopeKind, :scopeValue, :justification, :approvedBy, :now, :expiresAt) RETURNING id")
                .param("marketplaceId", marketplaceId)
                .param("ruleId", ruleId)
                .param("scopeKind", scope.stored())
                .param("scopeValue", scopeValue)
                .param("justification", justification)
                .param("approvedBy", approvedBy)
                .param("now", OffsetDateTime.now())
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        return findById(id).orElseThrow();
    }

    public Optional<Waiver> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vetting_waivers w"
                        + " JOIN marketplaces m ON m.id = w.marketplace_id WHERE w.id = :id")
                .param("id", id)
                .query(WaiverRepository::map)
                .optional();
    }

    /**
     * Every waiver of one marketplace, newest first. The evaluation filters by rule and scope in
     * memory rather than in SQL: the scope-matching rule is the trust-boundary decision, and it
     * belongs in one tested place ({@link Waiver#covers}) instead of being restated as a LIKE
     * pattern that could drift from it.
     */
    public List<Waiver> byMarketplace(long marketplaceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vetting_waivers w"
                        + " JOIN marketplaces m ON m.id = w.marketplace_id"
                        + " WHERE w.marketplace_id = :marketplaceId ORDER BY w.id DESC")
                .param("marketplaceId", marketplaceId)
                .query(WaiverRepository::map)
                .list();
    }

    /** Withdraws a waiver. A waiver already revoked is left alone, so the first revoker is kept. */
    public boolean revoke(long id, String revokedBy) {
        return jdbc.sql("UPDATE vetting_waivers SET revoked_at = :now, revoked_by = :revokedBy"
                                + " WHERE id = :id AND revoked_at IS NULL")
                        .param("now", OffsetDateTime.now())
                        .param("revokedBy", revokedBy)
                        .param("id", id)
                        .update()
                > 0;
    }

    /** The sweep's selection: lapsed, not revoked, not yet noted in the ledger. */
    public List<Waiver> newlyExpired(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vetting_waivers w"
                        + " JOIN marketplaces m ON m.id = w.marketplace_id"
                        + " WHERE w.expires_at <= :now AND w.expired_recorded_at IS NULL"
                        + " AND w.revoked_at IS NULL ORDER BY w.expires_at LIMIT :limit")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(WaiverRepository::map)
                .list();
    }

    /** Stamps a waiver as having had its expiry noted, so the ledger entry is written once. */
    public void markExpiryRecorded(long id) {
        jdbc.sql("UPDATE vetting_waivers SET expired_recorded_at = :now"
                        + " WHERE id = :id AND expired_recorded_at IS NULL")
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    private static Waiver map(ResultSet rs, int rowNum) throws SQLException {
        return new Waiver(
                rs.getLong("id"),
                rs.getLong("marketplace_id"),
                rs.getString("marketplace"),
                rs.getString("rule_id"),
                WaiverScope.of(rs.getString("scope_kind")),
                rs.getString("scope_value"),
                rs.getString("justification"),
                rs.getString("approved_by"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "revoked_at"),
                rs.getString("revoked_by"),
                instant(rs, "expired_recorded_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
