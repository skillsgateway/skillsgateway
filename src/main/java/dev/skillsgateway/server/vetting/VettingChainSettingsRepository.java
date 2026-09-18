package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the two chain settings (GW_VETTING_0032.1, GW_VETTING_0033.1), shaped like
 * {@link VetterToggleRepository}: one row per scope, upserted in place on {@code marketplace_id}
 * with {@code NULLS NOT DISTINCT}, so a scope never has two settings and the audit ledger — not
 * this table — carries the history.
 */
@Repository
public class VettingChainSettingsRepository {

    private final JdbcClient jdbc;

    public VettingChainSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_VETTING_0032.1"})
    public ChainModeSetting setMode(Long marketplaceId, ChainMode mode, String reason, String updatedBy) {
        return jdbc.sql("INSERT INTO vetting_chain_modes (marketplace_id, mode, reason, updated_by, updated_at)"
                        + " VALUES (:marketplaceId, :mode::vetting_chain_mode_mode, :reason, :updatedBy, :now)"
                        + " ON CONFLICT (marketplace_id) DO UPDATE"
                        + " SET mode = :mode::vetting_chain_mode_mode, reason = :reason,"
                        + " updated_by = :updatedBy, updated_at = :now"
                        + " RETURNING *")
                .param("marketplaceId", marketplaceId)
                .param("mode", mode.stored())
                .param("reason", reason)
                .param("updatedBy", updatedBy)
                .param("now", OffsetDateTime.now())
                .query(VettingChainSettingsRepository::mapMode)
                .single();
    }

    /**
     * Stores the order for one scope. The names are passed as a PostgreSQL array literal and cast,
     * rather than through a driver-created array: the caller has already refused every name that no
     * configured vetter carries, so what reaches here is a closed set of bean names.
     */
    @Requirements({"GW_VETTING_0033.1"})
    public ChainOrderSetting setOrder(Long marketplaceId, List<String> vetters, String reason, String updatedBy) {
        return jdbc.sql("INSERT INTO vetting_chain_orders (marketplace_id, vetters, reason, updated_by, updated_at)"
                        + " VALUES (:marketplaceId, :vetters::text[], :reason, :updatedBy, :now)"
                        + " ON CONFLICT (marketplace_id) DO UPDATE"
                        + " SET vetters = :vetters::text[], reason = :reason,"
                        + " updated_by = :updatedBy, updated_at = :now"
                        + " RETURNING *")
                .param("marketplaceId", marketplaceId)
                .param("vetters", arrayLiteral(vetters))
                .param("reason", reason)
                .param("updatedBy", updatedBy)
                .param("now", OffsetDateTime.now())
                .query(VettingChainSettingsRepository::mapOrder)
                .single();
    }

    /**
     * Removes a marketplace's mode override, so the scope resolves from the global setting or the
     * default again (GW_VETTING_0036). Answers whether a row actually went away: the caller audits
     * a removal, and a scope that had nothing to remove is not one.
     */
    @Requirements({"GW_VETTING_0036"})
    public boolean deleteMode(long marketplaceId) {
        return jdbc.sql("DELETE FROM vetting_chain_modes WHERE marketplace_id = :marketplaceId")
                        .param("marketplaceId", marketplaceId)
                        .update()
                > 0;
    }

    /** The same for a marketplace's order override (GW_VETTING_0036). */
    @Requirements({"GW_VETTING_0036"})
    public boolean deleteOrder(long marketplaceId) {
        return jdbc.sql("DELETE FROM vetting_chain_orders WHERE marketplace_id = :marketplaceId")
                        .param("marketplaceId", marketplaceId)
                        .update()
                > 0;
    }

    public Optional<ChainModeSetting> findMode(long marketplaceId) {
        return jdbc.sql("SELECT * FROM vetting_chain_modes WHERE marketplace_id = :marketplaceId")
                .param("marketplaceId", marketplaceId)
                .query(VettingChainSettingsRepository::mapMode)
                .optional();
    }

    public Optional<ChainModeSetting> findGlobalMode() {
        return jdbc.sql("SELECT * FROM vetting_chain_modes WHERE marketplace_id IS NULL")
                .query(VettingChainSettingsRepository::mapMode)
                .optional();
    }

    public Optional<ChainOrderSetting> findOrder(long marketplaceId) {
        return jdbc.sql("SELECT * FROM vetting_chain_orders WHERE marketplace_id = :marketplaceId")
                .param("marketplaceId", marketplaceId)
                .query(VettingChainSettingsRepository::mapOrder)
                .optional();
    }

    public Optional<ChainOrderSetting> findGlobalOrder() {
        return jdbc.sql("SELECT * FROM vetting_chain_orders WHERE marketplace_id IS NULL")
                .query(VettingChainSettingsRepository::mapOrder)
                .optional();
    }

    /** Every mode setting, globals first, in a stable order. */
    public List<ChainModeSetting> listModes() {
        return jdbc.sql("SELECT * FROM vetting_chain_modes ORDER BY marketplace_id NULLS FIRST")
                .query(VettingChainSettingsRepository::mapMode)
                .list();
    }

    /** Every order setting, globals first, in a stable order. */
    public List<ChainOrderSetting> listOrders() {
        return jdbc.sql("SELECT * FROM vetting_chain_orders ORDER BY marketplace_id NULLS FIRST")
                .query(VettingChainSettingsRepository::mapOrder)
                .list();
    }

    private static ChainModeSetting mapMode(ResultSet rs, int rowNum) throws SQLException {
        return new ChainModeSetting(
                rs.getLong("id"),
                marketplaceId(rs),
                ChainMode.of(rs.getString("mode")),
                rs.getString("reason"),
                rs.getString("updated_by"),
                instant(rs, "updated_at"));
    }

    private static ChainOrderSetting mapOrder(ResultSet rs, int rowNum) throws SQLException {
        Array array = rs.getArray("vetters");
        List<String> vetters = array == null ? List.of() : List.of((String[]) array.getArray());
        return new ChainOrderSetting(
                rs.getLong("id"),
                marketplaceId(rs),
                vetters,
                rs.getString("reason"),
                rs.getString("updated_by"),
                instant(rs, "updated_at"));
    }

    private static Long marketplaceId(ResultSet rs) throws SQLException {
        long value = rs.getLong("marketplace_id");
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code {"a","b"}} — the array literal form, with every element quoted. */
    private static String arrayLiteral(List<String> values) {
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"')
                    .append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return literal.append('}').toString();
    }
}
