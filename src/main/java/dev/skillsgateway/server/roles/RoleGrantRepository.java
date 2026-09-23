package dev.skillsgateway.server.roles;

import io.github.reqstool.annotations.Requirements;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoleGrantRepository {

    /**
     * Every read joins the marketplace name in: grants are shown and matched by name, not id. So a
     * grant on a removed marketplace is excluded from every read (GW_INGEST_0035), or it would match a
     * new marketplace registered under the same name.
     */
    private static final String SELECT = "SELECT g.id, g.principal, g.role, m.name AS marketplace,"
            + " g.granted_by, g.granted_at"
            + " FROM role_grants g LEFT JOIN marketplaces m ON m.id = g.marketplace_id"
            + " WHERE (g.marketplace_id IS NULL OR m.deleted_at IS NULL)";

    private final JdbcClient jdbc;

    public RoleGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a grant, or returns empty when the identical grant already exists — the caller
     * turns that into a conflict answer (GW_AUTH_0013).
     */
    @Requirements({"GW_AUTH_0013", "GW_FACADE_0009"})
    public Optional<RoleGrant> insert(String principal, String role, Long marketplaceId, String grantedBy) {
        try {
            long id = jdbc.sql(
                            "INSERT INTO role_grants (principal, role, marketplace_id, granted_by, granted_at)"
                                    + " VALUES (:principal, :role::role_grant_role, :marketplaceId, :grantedBy, :now) RETURNING id")
                    .param("principal", principal)
                    .param("role", role)
                    .param("marketplaceId", marketplaceId)
                    .param("grantedBy", grantedBy)
                    .param("now", OffsetDateTime.now())
                    .query(Long.class)
                    .single();
            return findById(id);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Optional<RoleGrant> findById(long id) {
        return jdbc.sql(SELECT + " AND g.id = :id")
                .param("id", id)
                .query(RoleGrant.class)
                .optional();
    }

    @Requirements({"GW_INGEST_0035"})
    public List<RoleGrant> findByPrincipal(String principal) {
        return jdbc.sql(SELECT + " AND g.principal = :principal ORDER BY g.id")
                .param("principal", principal)
                .query(RoleGrant.class)
                .list();
    }

    public List<RoleGrant> list() {
        return jdbc.sql(SELECT + " ORDER BY g.principal, g.id")
                .query(RoleGrant.class)
                .list();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM role_grants WHERE id = :id")
                        .param("id", id)
                        .update()
                == 1;
    }
}
