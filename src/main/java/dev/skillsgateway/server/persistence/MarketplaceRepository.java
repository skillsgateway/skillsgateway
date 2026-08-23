package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MarketplaceRepository {

    private final JdbcClient jdbc;

    public MarketplaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_0001"})
    public Marketplace register(String name, String url) {
        return register(name, url, null);
    }

    @Requirements({"GW_0021"})
    public Marketplace register(String name, String url, ForgeMetadata metadata) {
        return register(name, url, metadata, Marketplace.ORIGIN_UPSTREAM, Marketplace.PUSH_APPEND_ONLY);
    }

    /**
     * The full insert. A hosted marketplace (GW_0101) passes a null url and carries a push policy;
     * the table's CHECK constraints are what make the two shapes mutually exclusive rather than
     * anything here.
     */
    @Requirements({"GW_0021", "GW_0101"})
    public Marketplace register(String name, String url, ForgeMetadata metadata, String origin, String pushPolicy) {
        return jdbc.sql("INSERT INTO marketplaces"
                        + " (name, url, created_at, origin, push_policy, forge, forge_project, description,"
                        + " upstream_updated_at)"
                        + " VALUES (:name, :url, :now, :origin, :pushPolicy, :forge, :forgeProject, :description,"
                        + " :upstreamUpdatedAt)"
                        + " RETURNING *")
                .param("name", name)
                .param("url", url)
                .param("origin", origin)
                .param("pushPolicy", pushPolicy)
                .param("now", OffsetDateTime.now())
                .param("forge", metadata == null ? null : metadata.forge())
                .param("forgeProject", metadata == null ? null : metadata.project())
                .param("description", metadata == null ? null : metadata.description())
                .param(
                        "upstreamUpdatedAt",
                        metadata == null || metadata.updatedAt() == null
                                ? null
                                : metadata.updatedAt().atOffset(java.time.ZoneOffset.UTC))
                .query(MarketplaceRepository::map)
                .single();
    }

    /** Forge metadata captured best-effort at registration. */
    public record ForgeMetadata(String forge, String project, String description, Instant updatedAt) {}

    public Optional<Marketplace> findByName(String name) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE name = :name")
                .param("name", name)
                .query(MarketplaceRepository::map)
                .optional();
    }

    public Optional<Marketplace> findById(long id) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE id = :id")
                .param("id", id)
                .query(MarketplaceRepository::map)
                .optional();
    }

    public List<Marketplace> list() {
        return jdbc.sql("SELECT * FROM marketplaces ORDER BY name")
                .query(MarketplaceRepository::map)
                .list();
    }

    /**
     * Sets the sync mode, and with it the webhook secret: the caller passes the freshly generated
     * secret when the new mode is webhook and null otherwise, so leaving webhook mode always
     * discards the key (GW_0056, GW_0058).
     */
    @Requirements({"GW_0056"})
    public Optional<Marketplace> updateSyncMode(String name, String mode, String webhookSecret) {
        return jdbc.sql("UPDATE marketplaces SET sync_mode = :mode, webhook_secret = :secret"
                        + " WHERE name = :name RETURNING *")
                .param("mode", mode)
                .param("secret", webhookSecret)
                .param("name", name)
                .query(MarketplaceRepository::map)
                .optional();
    }

    /** The HMAC key for the inbound webhook; empty when the marketplace is not in webhook mode. */
    public Optional<String> webhookSecret(String name) {
        return jdbc.sql("SELECT webhook_secret FROM marketplaces WHERE name = :name AND webhook_secret IS NOT NULL")
                .param("name", name)
                .query(String.class)
                .optional();
    }

    /** The scheduled sweep's queue: scheduled marketplaces, least recently attempted first (GW_0057). */
    @Requirements({"GW_0057"})
    public List<Marketplace> dueScheduledSync(int limit) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE sync_mode = 'scheduled'"
                        + " ORDER BY last_sync_at ASC NULLS FIRST, id ASC LIMIT :limit")
                .param("limit", limit)
                .query(MarketplaceRepository::map)
                .list();
    }

    /** Stamped per attempt, success or failure, so a dead upstream cannot monopolize the queue. */
    public void stampSyncAttempt(long id) {
        jdbc.sql("UPDATE marketplaces SET last_sync_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    static Marketplace map(ResultSet rs, int rowNum) throws SQLException {
        return new Marketplace(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("url"),
                instant(rs, "created_at"),
                rs.getString("origin"),
                rs.getString("push_policy"),
                rs.getString("forge"),
                rs.getString("forge_project"),
                rs.getString("description"),
                instant(rs, "upstream_updated_at"),
                rs.getString("sync_mode"),
                instant(rs, "last_sync_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
