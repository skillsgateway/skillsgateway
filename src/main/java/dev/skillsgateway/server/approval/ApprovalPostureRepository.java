package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.approval.ApprovalPostureService.Posture;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The posture columns of {@code marketplaces} and the {@code auto_approval_overrides} table
 * (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3). Kept apart from {@code MarketplaceRepository} so the
 * marketplace record the API returns does not grow fields only this capability reads.
 */
@Repository
public class ApprovalPostureRepository {

    /** The stored posture; {@code basis} is never stored, it is computed at every read. */
    public record StoredPosture(Posture posture, String setBy, Instant setAt, String reason, Instant expiresAt) {}

    public record StoredOverride(
            long id, long marketplaceId, String reason, String createdBy, Instant createdAt, Instant expiresAt) {}

    private final JdbcClient jdbc;

    public ApprovalPostureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises posture changes on one marketplace, so two grants cannot interleave their override writes. */
    public void lock(long marketplaceId) {
        jdbc.sql("SELECT id FROM marketplaces WHERE id = :id FOR UPDATE")
                .param("id", marketplaceId)
                .query(Long.class)
                .optional();
    }

    public StoredPosture find(long marketplaceId) {
        return jdbc.sql("SELECT approval_posture::text AS posture, approval_set_by, approval_set_at, approval_reason,"
                        + " approval_expires_at FROM marketplaces WHERE id = :id")
                .param("id", marketplaceId)
                .query((rs, rowNum) -> new StoredPosture(
                        Posture.valueOf(rs.getString("posture").toUpperCase(Locale.ROOT)),
                        rs.getString("approval_set_by"),
                        instant(rs, "approval_set_at"),
                        rs.getString("approval_reason"),
                        instant(rs, "approval_expires_at")))
                .single();
    }

    public void save(long marketplaceId, StoredPosture posture) {
        jdbc.sql("UPDATE marketplaces SET approval_posture = :posture::marketplace_approval_posture,"
                        + " approval_set_by = :setBy, approval_set_at = :setAt, approval_reason = :reason,"
                        + " approval_expires_at = :expiresAt WHERE id = :id")
                .param("posture", posture.posture().wire())
                .param("setBy", posture.setBy())
                .param("setAt", offset(posture.setAt()))
                .param("reason", posture.reason())
                .param("expiresAt", offset(posture.expiresAt()))
                .param("id", marketplaceId)
                .update();
    }

    /** The override that is neither revoked nor recorded as expired, whether or not its expiry has passed. */
    /** Live marketplaces whose stored posture is auto, lapsed or not: the attempt decides that. */
    public List<Long> autoMarketplaces() {
        return jdbc.sql(
                        "SELECT id FROM marketplaces WHERE approval_posture = 'auto' AND deleted_at IS NULL ORDER BY id")
                .query(Long.class)
                .list();
    }

    public Optional<StoredOverride> findOpen(long marketplaceId) {
        return jdbc.sql("SELECT * FROM auto_approval_overrides WHERE marketplace_id = :id"
                        + " AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                .param("id", marketplaceId)
                .query(ApprovalPostureRepository::mapOverride)
                .optional();
    }

    public StoredOverride createOverride(
            long marketplaceId, String reason, String createdBy, Instant createdAt, Instant expiresAt) {
        return jdbc.sql(
                        "INSERT INTO auto_approval_overrides (marketplace_id, reason, created_by, created_at, expires_at)"
                                + " VALUES (:id, :reason, :createdBy, :createdAt, :expiresAt) RETURNING *")
                .param("id", marketplaceId)
                .param("reason", reason)
                .param("createdBy", createdBy)
                .param("createdAt", offset(createdAt))
                .param("expiresAt", offset(expiresAt))
                .query(ApprovalPostureRepository::mapOverride)
                .single();
    }

    /** True for the one caller that revoked it; a repeat or a race finds nothing open. */
    public boolean revoke(long overrideId, String revokedBy, Instant now) {
        return jdbc.sql("UPDATE auto_approval_overrides SET revoked_at = :now, revoked_by = :by"
                                + " WHERE id = :id AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                        .param("now", offset(now))
                        .param("by", revokedBy)
                        .param("id", overrideId)
                        .update()
                == 1;
    }

    /** True for the one caller that recorded the lapse, which is what makes it recorded once. */
    public boolean recordExpired(long overrideId, Instant now) {
        return jdbc.sql("UPDATE auto_approval_overrides SET expired_recorded_at = :now WHERE id = :id"
                                + " AND expires_at <= :now AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                        .param("now", offset(now))
                        .param("id", overrideId)
                        .update()
                == 1;
    }

    private static StoredOverride mapOverride(ResultSet rs, int rowNum) throws SQLException {
        return new StoredOverride(
                rs.getLong("id"),
                rs.getLong("marketplace_id"),
                rs.getString("reason"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
