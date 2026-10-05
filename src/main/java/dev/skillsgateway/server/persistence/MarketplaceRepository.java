package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MarketplaceRepository {

    private final JdbcClient jdbc;

    public MarketplaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_INGEST_0001"})
    public Marketplace register(String name, String url) {
        return register(name, url, null);
    }

    @Requirements({"GW_INGEST_0009"})
    public Marketplace register(String name, String url, ForgeMetadata metadata) {
        return register(name, url, metadata, Marketplace.ORIGIN_UPSTREAM, Marketplace.PUSH_APPEND_ONLY, null);
    }

    /**
     * The full insert. A hosted marketplace (GW_FACADE_0006) passes a null url and carries a push policy;
     * the table's CHECK constraints are what make the two shapes mutually exclusive rather than
     * anything here.
     */
    @Requirements({"GW_INGEST_0009", "GW_APPROVAL_0010", "GW_FACADE_0006", "GW_FACADE_0009"})
    public Marketplace register(
            String name, String url, ForgeMetadata metadata, String origin, String pushPolicy, String registeredBy) {
        return jdbc.sql("INSERT INTO marketplaces"
                        + " (name, url, created_at, registered_by, origin, push_policy, forge, forge_project,"
                        + " description, upstream_updated_at)"
                        + " VALUES (:name, :url, :now, :registeredBy, :origin::marketplace_origin,"
                        + " :pushPolicy::marketplace_push_policy, :forge, :forgeProject,"
                        + " :description, :upstreamUpdatedAt)"
                        + " RETURNING *")
                .param("name", name)
                .param("registeredBy", registeredBy)
                .param("url", url)
                .param("origin", origin == null ? Marketplace.ORIGIN_UPSTREAM : origin)
                // The column's DEFAULT applies only to an omitted value, not to an explicit null.
                .param("pushPolicy", pushPolicy == null ? Marketplace.PUSH_APPEND_ONLY : pushPolicy)
                .param("now", OffsetDateTime.now())
                .param("forge", metadata == null ? null : metadata.forge())
                .param("forgeProject", metadata == null ? null : metadata.project())
                .param("description", metadata == null ? null : metadata.description())
                .param(
                        "upstreamUpdatedAt",
                        metadata == null || metadata.updatedAt() == null
                                ? null
                                : metadata.updatedAt().atOffset(java.time.ZoneOffset.UTC))
                .query(Marketplace.class)
                .single();
    }

    /** Forge metadata captured best-effort at registration. */
    public record ForgeMetadata(String forge, String project, String description, Instant updatedAt) {}

    /**
     * The live marketplace of a name. Every name-addressed read filters out removed marketplaces
     * (GW_INGEST_0034): the name is how the facade, sync, push and every {@code /marketplaces/{name}}
     * route reach a marketplace, so this one predicate is what takes a removed one out of all of them.
     */
    @Requirements({"GW_INGEST_0034"})
    public Optional<Marketplace> findByName(String name) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE name = :name AND deleted_at IS NULL")
                .param("name", name)
                .query(Marketplace.class)
                .optional();
    }

    /** Any marketplace, removed or not: a snapshot's provenance must outlive its marketplace's removal. */
    public Optional<Marketplace> findById(long id) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE id = :id")
                .param("id", id)
                .query(Marketplace.class)
                .optional();
    }

    public List<Marketplace> list() {
        return jdbc.sql("SELECT * FROM marketplaces WHERE deleted_at IS NULL ORDER BY name")
                .query(Marketplace.class)
                .list();
    }

    /**
     * Sets the sync mode, and with it the webhook secret: the caller passes the freshly generated
     * secret when the new mode is webhook and null otherwise, so leaving webhook mode always
     * discards the key (GW_INGEST_0010, GW_INGEST_0012).
     */
    @Requirements({"GW_INGEST_0010", "GW_FACADE_0009"})
    public Optional<Marketplace> updateSyncMode(String name, String mode, String webhookSecret) {
        return jdbc.sql("UPDATE marketplaces SET sync_mode = :mode::marketplace_sync_mode, webhook_secret = :secret"
                        + " WHERE name = :name AND deleted_at IS NULL RETURNING *")
                .param("mode", mode)
                .param("secret", webhookSecret)
                .param("name", name)
                .query(Marketplace.class)
                .optional();
    }

    /** The HMAC key for the inbound webhook; empty when the marketplace is not in webhook mode. */
    public Optional<String> webhookSecret(String name) {
        return jdbc.sql("SELECT webhook_secret FROM marketplaces WHERE name = :name AND deleted_at IS NULL"
                        + " AND webhook_secret IS NOT NULL")
                .param("name", name)
                .query(String.class)
                .optional();
    }

    /** The scheduled sweep's queue: scheduled marketplaces, least recently attempted first (GW_INGEST_0011). */
    @Requirements({"GW_INGEST_0011"})
    public List<Marketplace> dueScheduledSync(int limit) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE sync_mode = 'scheduled' AND deleted_at IS NULL"
                        + " ORDER BY last_sync_at ASC NULLS FIRST, id ASC LIMIT :limit")
                .param("limit", limit)
                .query(Marketplace.class)
                .list();
    }

    /** Stamped per attempt, success or failure, so a dead upstream cannot monopolize the queue. */
    public void stampSyncAttempt(long id) {
        jdbc.sql("UPDATE marketplaces SET last_sync_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    /**
     * Replaces the record of the last ingest attempt (GW_INGEST_0039): one row per marketplace, so it
     * stays bounded; the sequence of attempts is the ledger's.
     */
    @Requirements({"GW_INGEST_0039"})
    public void recordIngest(long id, String outcome, String reason) {
        jdbc.sql("UPDATE marketplaces SET last_ingest_at = :now,"
                        + " last_ingest_outcome = :outcome::marketplace_last_ingest_outcome,"
                        + " last_ingest_reason = :reason WHERE id = :id")
                .param("now", OffsetDateTime.now())
                .param("outcome", outcome)
                .param("reason", reason)
                .param("id", id)
                .update();
    }

    /**
     * Retires a live marketplace (GW_INGEST_0034). Conditional on the row still being live, so of two
     * concurrent removals exactly one wins; the row lock this update takes is also what an approval's
     * decision waits on (see {@code SnapshotRepository.decide}).
     *
     * @return when the removal was recorded, or empty when the marketplace was no longer live
     */
    @Requirements({"GW_INGEST_0034"})
    public Optional<Instant> retire(long id, String actor, String reason) {
        return jdbc.sql("UPDATE marketplaces SET deleted_at = :now, deleted_by = :actor, deleted_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL RETURNING deleted_at")
                .param("now", OffsetDateTime.now())
                .param("actor", actor)
                .param("reason", reason)
                .param("id", id)
                .query(Instant.class)
                .optional();
    }

    /** What {@link #changeUrlBeforeFirstSnapshot} did. */
    public enum UrlChange {
        CHANGED,
        NOT_LIVE,
        HAS_SNAPSHOT
    }

    /** Whether any snapshot of this marketplace exists, in any state (GW_INGEST_0066). */
    public boolean hasSnapshot(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM snapshots WHERE marketplace_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /**
     * Replaces the URL of a live marketplace that has no snapshot, with the forge metadata resolved
     * for it and the editor as registrant (GW_INGEST_0066). The row is locked before the snapshot check,
     * and {@code SnapshotRepository.create} takes it shared before recording a snapshot, so of an edit
     * and a snapshot insert racing for one marketplace exactly one goes through.
     */
    @Requirements({"GW_INGEST_0066", "GW_APPROVAL_0010"})
    @Transactional
    public UrlChange changeUrlBeforeFirstSnapshot(long id, String url, ForgeMetadata metadata, String actor) {
        boolean live = jdbc.sql("SELECT deleted_at IS NULL FROM marketplaces WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!live) {
            return UrlChange.NOT_LIVE;
        }
        // A statement of its own, so it sees a snapshot committed while this one waited for the lock.
        if (hasSnapshot(id)) {
            return UrlChange.HAS_SNAPSHOT;
        }
        jdbc.sql("UPDATE marketplaces SET url = :url, registered_by = :actor, forge = :forge,"
                        + " forge_project = :forgeProject, description = :description,"
                        + " upstream_updated_at = :upstreamUpdatedAt WHERE id = :id")
                .param("url", url)
                .param("actor", actor)
                .param("forge", metadata == null ? null : metadata.forge())
                .param("forgeProject", metadata == null ? null : metadata.project())
                .param("description", metadata == null ? null : metadata.description())
                .param(
                        "upstreamUpdatedAt",
                        metadata == null || metadata.updatedAt() == null
                                ? null
                                : metadata.updatedAt().atOffset(java.time.ZoneOffset.UTC))
                .param("id", id)
                .update();
        return UrlChange.CHANGED;
    }

    /** Removed marketplaces that held this name, most recent first (GW_INGEST_0035). */
    @Requirements({"GW_INGEST_0035"})
    public List<Marketplace> retiredByName(String name) {
        return jdbc.sql("SELECT * FROM marketplaces WHERE name = :name AND deleted_at IS NOT NULL ORDER BY id DESC")
                .param("name", name)
                .query(Marketplace.class)
                .list();
    }

    /** Whether the marketplace with this id has been removed (GW_INGEST_0034). */
    public boolean removed(long id) {
        return jdbc.sql("SELECT deleted_at IS NOT NULL FROM marketplaces WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }
}
