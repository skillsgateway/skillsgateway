package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link VetterToggle} (GW_VETTING_0029.1). Upsert-in-place on the {@code (vetter,
 * marketplace_id)} pair — {@code NULLS NOT DISTINCT} in the schema — so a vetter never has two
 * settings at the same scope; the audit ledger, not this table, carries the history.
 */
@Repository
public class VetterToggleRepository {

    private final JdbcClient jdbc;

    public VetterToggleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sets the enablement of one vetter at one scope, creating the row or overwriting the
     * setting that is already there. {@code marketplaceId} null is the global scope.
     */
    @Requirements({"GW_VETTING_0029.1"})
    public VetterToggle set(String vetter, Long marketplaceId, boolean enabled, String reason, String updatedBy) {
        return jdbc.sql("INSERT INTO vetter_toggles (vetter, marketplace_id, enabled, reason, updated_by, updated_at)"
                        + " VALUES (:vetter, :marketplaceId, :enabled, :reason, :updatedBy, :now)"
                        + " ON CONFLICT (vetter, marketplace_id) DO UPDATE"
                        + " SET enabled = :enabled, reason = :reason, updated_by = :updatedBy, updated_at = :now"
                        + " RETURNING *")
                .param("vetter", vetter)
                .param("marketplaceId", marketplaceId)
                .param("enabled", enabled)
                .param("reason", reason)
                .param("updatedBy", updatedBy)
                .param("now", OffsetDateTime.now())
                .query(VetterToggle.class)
                .single();
    }

    /**
     * Removes a marketplace's override of one vetter, so the vetter resolves from the global
     * setting or the default again (GW_VETTING_0036). Answers whether a row actually went away.
     */
    @Requirements({"GW_VETTING_0036"})
    public boolean delete(String vetter, long marketplaceId) {
        return jdbc.sql("DELETE FROM vetter_toggles WHERE vetter = :vetter AND marketplace_id = :marketplaceId")
                        .param("vetter", vetter)
                        .param("marketplaceId", marketplaceId)
                        .update()
                > 0;
    }

    /** Every override one marketplace holds, so a clear of all of them can audit each by name. */
    public List<VetterToggle> listFor(long marketplaceId) {
        return jdbc.sql("SELECT * FROM vetter_toggles WHERE marketplace_id = :marketplaceId ORDER BY vetter")
                .param("marketplaceId", marketplaceId)
                .query(VetterToggle.class)
                .list();
    }

    /** The per-marketplace setting for a vetter, if one exists. */
    public Optional<VetterToggle> find(String vetter, long marketplaceId) {
        return jdbc.sql("SELECT * FROM vetter_toggles WHERE vetter = :vetter" + " AND marketplace_id = :marketplaceId")
                .param("vetter", vetter)
                .param("marketplaceId", marketplaceId)
                .query(VetterToggle.class)
                .optional();
    }

    /** The global setting for a vetter, if one exists. */
    public Optional<VetterToggle> findGlobal(String vetter) {
        return jdbc.sql("SELECT * FROM vetter_toggles WHERE vetter = :vetter AND marketplace_id IS NULL")
                .param("vetter", vetter)
                .query(VetterToggle.class)
                .optional();
    }

    /**
     * Every setting, globals and per-marketplace, in a stable order. A removed marketplace's rows stay
     * in the table and leave the listing (GW_INGEST_0034): they govern nothing any more.
     */
    @Requirements({"GW_INGEST_0034"})
    public List<VetterToggle> list() {
        return jdbc.sql("SELECT * FROM vetter_toggles"
                        + " WHERE marketplace_id IS NULL OR marketplace_id IN (SELECT id FROM marketplaces WHERE deleted_at IS NULL)"
                        + " ORDER BY vetter, marketplace_id NULLS FIRST")
                .query(VetterToggle.class)
                .list();
    }
}
