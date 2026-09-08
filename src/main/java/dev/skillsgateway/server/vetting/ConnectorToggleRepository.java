package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link ConnectorToggle} (GW_VETTING_0029.1). Upsert-in-place on the {@code (connector,
 * marketplace_id)} pair — {@code NULLS NOT DISTINCT} in the schema — so a connector never has two
 * settings at the same scope; the audit ledger, not this table, carries the history.
 */
@Repository
public class ConnectorToggleRepository {

    private final JdbcClient jdbc;

    public ConnectorToggleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sets the enablement of one connector at one scope, creating the row or overwriting the
     * setting that is already there. {@code marketplaceId} null is the global scope.
     */
    @Requirements({"GW_VETTING_0029.1"})
    public ConnectorToggle set(String connector, Long marketplaceId, boolean enabled, String reason, String updatedBy) {
        return jdbc.sql(
                        "INSERT INTO connector_toggles (connector, marketplace_id, enabled, reason, updated_by, updated_at)"
                                + " VALUES (:connector, :marketplaceId, :enabled, :reason, :updatedBy, :now)"
                                + " ON CONFLICT (connector, marketplace_id) DO UPDATE"
                                + " SET enabled = :enabled, reason = :reason, updated_by = :updatedBy, updated_at = :now"
                                + " RETURNING *")
                .param("connector", connector)
                .param("marketplaceId", marketplaceId)
                .param("enabled", enabled)
                .param("reason", reason)
                .param("updatedBy", updatedBy)
                .param("now", OffsetDateTime.now())
                .query(ConnectorToggle.class)
                .single();
    }

    /** The per-marketplace setting for a connector, if one exists. */
    public Optional<ConnectorToggle> find(String connector, long marketplaceId) {
        return jdbc.sql("SELECT * FROM connector_toggles WHERE connector = :connector"
                        + " AND marketplace_id = :marketplaceId")
                .param("connector", connector)
                .param("marketplaceId", marketplaceId)
                .query(ConnectorToggle.class)
                .optional();
    }

    /** The global setting for a connector, if one exists. */
    public Optional<ConnectorToggle> findGlobal(String connector) {
        return jdbc.sql("SELECT * FROM connector_toggles WHERE connector = :connector AND marketplace_id IS NULL")
                .param("connector", connector)
                .query(ConnectorToggle.class)
                .optional();
    }

    /** Every setting, globals and per-marketplace, in a stable order. */
    public List<ConnectorToggle> list() {
        return jdbc.sql("SELECT * FROM connector_toggles ORDER BY connector, marketplace_id NULLS FIRST")
                .query(ConnectorToggle.class)
                .list();
    }
}
