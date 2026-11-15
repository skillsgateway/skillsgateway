package dev.skillsgateway.server.approval;

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
 * The automatic-approval columns of {@code marketplaces} and the {@code auto_approval_exemptions}
 * table (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3). Kept apart from {@code MarketplaceRepository} so
 * the marketplace record the API returns does not grow fields only this capability reads.
 */
@Repository
public class AutoApprovalSettingRepository {

    /** The stored setting; what allows it is never stored, it is computed at every read. */
    public record StoredSetting(boolean enabled, String setBy, Instant setAt, String reason, Instant expiresAt) {}

    public record StoredExemption(
            long id, long marketplaceId, String reason, String createdBy, Instant createdAt, Instant expiresAt) {}

    private final JdbcClient jdbc;

    public AutoApprovalSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises changes on one marketplace, so two grants cannot interleave their exemption writes. */
    public void lock(long marketplaceId) {
        jdbc.sql("SELECT id FROM marketplaces WHERE id = :id FOR UPDATE")
                .param("id", marketplaceId)
                .query(Long.class)
                .optional();
    }

    public StoredSetting find(long marketplaceId) {
        return jdbc.sql("SELECT auto_approval_enabled, auto_approval_set_by, auto_approval_set_at,"
                        + " auto_approval_reason, auto_approval_expires_at FROM marketplaces WHERE id = :id")
                .param("id", marketplaceId)
                .query((rs, rowNum) -> new StoredSetting(
                        rs.getBoolean("auto_approval_enabled"),
                        rs.getString("auto_approval_set_by"),
                        instant(rs, "auto_approval_set_at"),
                        rs.getString("auto_approval_reason"),
                        instant(rs, "auto_approval_expires_at")))
                .single();
    }

    public void save(long marketplaceId, StoredSetting setting) {
        jdbc.sql("UPDATE marketplaces SET auto_approval_enabled = :enabled,"
                        + " auto_approval_set_by = :setBy, auto_approval_set_at = :setAt,"
                        + " auto_approval_reason = :reason, auto_approval_expires_at = :expiresAt WHERE id = :id")
                .param("enabled", setting.enabled())
                .param("setBy", setting.setBy())
                .param("setAt", offset(setting.setAt()))
                .param("reason", setting.reason())
                .param("expiresAt", offset(setting.expiresAt()))
                .param("id", marketplaceId)
                .update();
    }

    /** Live marketplaces with automatic approval turned on, lapsed or not: the attempt decides that. */
    public List<Long> enabledMarketplaces() {
        return jdbc.sql("SELECT id FROM marketplaces WHERE auto_approval_enabled AND deleted_at IS NULL ORDER BY id")
                .query(Long.class)
                .list();
    }

    /** The exemption that is neither revoked nor recorded as expired, whether or not its expiry has passed. */
    public Optional<StoredExemption> findOpen(long marketplaceId) {
        return jdbc.sql("SELECT * FROM auto_approval_exemptions WHERE marketplace_id = :id"
                        + " AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                .param("id", marketplaceId)
                .query(AutoApprovalSettingRepository::mapExemption)
                .optional();
    }

    public StoredExemption createExemption(
            long marketplaceId, String reason, String createdBy, Instant createdAt, Instant expiresAt) {
        return jdbc.sql(
                        "INSERT INTO auto_approval_exemptions (marketplace_id, reason, created_by, created_at, expires_at)"
                                + " VALUES (:id, :reason, :createdBy, :createdAt, :expiresAt) RETURNING *")
                .param("id", marketplaceId)
                .param("reason", reason)
                .param("createdBy", createdBy)
                .param("createdAt", offset(createdAt))
                .param("expiresAt", offset(expiresAt))
                .query(AutoApprovalSettingRepository::mapExemption)
                .single();
    }

    /** True for the one caller that revoked it; a repeat or a race finds nothing open. */
    public boolean revoke(long exemptionId, String revokedBy, Instant now) {
        return jdbc.sql("UPDATE auto_approval_exemptions SET revoked_at = :now, revoked_by = :by"
                                + " WHERE id = :id AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                        .param("now", offset(now))
                        .param("by", revokedBy)
                        .param("id", exemptionId)
                        .update()
                == 1;
    }

    /** True for the one caller that recorded the lapse, which is what makes it recorded once. */
    public boolean recordExpired(long exemptionId, Instant now) {
        return jdbc.sql("UPDATE auto_approval_exemptions SET expired_recorded_at = :now WHERE id = :id"
                                + " AND expires_at <= :now AND revoked_at IS NULL AND expired_recorded_at IS NULL")
                        .param("now", offset(now))
                        .param("id", exemptionId)
                        .update()
                == 1;
    }

    private static StoredExemption mapExemption(ResultSet rs, int rowNum) throws SQLException {
        return new StoredExemption(
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
