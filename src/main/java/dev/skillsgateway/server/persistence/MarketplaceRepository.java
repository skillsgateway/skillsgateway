package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
     * stays bounded; the sequence of attempts is the ledger's. Clears the running ingest only where it
     * is still this attempt's (GW_INGEST_0066), so a finishing attempt never hides a later one.
     *
     * @param failureJson the failure's parts as JSON, or null for a success
     */
    @Requirements({"GW_INGEST_0039", "GW_INGEST_0066"})
    public void recordIngest(
            long id, UUID attempt, String outcome, String reason, String failureJson, Long snapshotId) {
        jdbc.sql("UPDATE marketplaces SET last_ingest_at = now(),"
                        + " last_ingest_outcome = :outcome::marketplace_last_ingest_outcome,"
                        + " last_ingest_reason = :reason, last_ingest_failure = CAST(:failure AS jsonb),"
                        + " last_ingest_snapshot_id = :snapshotId,"
                        + RUNNING_COLUMNS_CLEARED_IF_MINE
                        + " WHERE id = :id")
                .param("outcome", outcome)
                .param("reason", reason)
                .param("failure", failureJson)
                .param("snapshotId", snapshotId)
                .param("attempt", attempt)
                .param("id", id)
                .update();
    }

    private static final String RUNNING_COLUMNS_CLEARED_IF_MINE =
            " ingest_stage = CASE WHEN ingest_attempt = :attempt THEN NULL ELSE ingest_stage END,"
                    + " ingest_started_at = CASE WHEN ingest_attempt = :attempt THEN NULL ELSE ingest_started_at END,"
                    + " ingest_heartbeat_at = CASE WHEN ingest_attempt = :attempt THEN NULL"
                    + " ELSE ingest_heartbeat_at END,"
                    + " ingest_attempt = CASE WHEN ingest_attempt = :attempt THEN NULL ELSE ingest_attempt END";

    /**
     * Claims the marketplace for an on-demand ingest (GW_INGEST_0066): succeeds only when no ingest is
     * running, or the running one's heartbeat is older than {@code stale} (GW_INGEST_0067). One
     * conditional update, so of two concurrent claims on any replicas exactly one wins.
     *
     * @return whether this attempt now owns the marketplace's running ingest, queued
     */
    @Requirements({"GW_INGEST_0066", "GW_INGEST_0067"})
    public boolean claimIngest(long id, UUID attempt, Duration stale) {
        return jdbc.sql("UPDATE marketplaces SET ingest_attempt = :attempt,"
                                + " ingest_stage = 'queued'::marketplace_ingest_stage,"
                                + " ingest_started_at = now(), ingest_heartbeat_at = now()"
                                + " WHERE id = :id AND (ingest_attempt IS NULL"
                                + " OR ingest_heartbeat_at < now() - make_interval(secs => :stale))")
                        .param("attempt", attempt)
                        .param("id", id)
                        .param("stale", (double) stale.toSeconds())
                        .update()
                > 0;
    }

    /**
     * The attempt that holds the per-marketplace lock is the one doing the work, so it takes the
     * running ingest unconditionally. A claimed attempt keeps its start time, so the wait it spent
     * queued counts towards the elapsed time the portal shows.
     */
    @Requirements({"GW_INGEST_0066"})
    public void startIngest(long id, UUID attempt) {
        jdbc.sql("UPDATE marketplaces SET"
                        + " ingest_started_at = CASE WHEN ingest_attempt = :attempt THEN ingest_started_at"
                        + " ELSE now() END,"
                        + " ingest_attempt = :attempt, ingest_stage = 'fetching'::marketplace_ingest_stage,"
                        + " ingest_heartbeat_at = now() WHERE id = :id")
                .param("attempt", attempt)
                .param("id", id)
                .update();
    }

    /** Moves this attempt to its next stage; a no-op once another attempt has taken over. */
    @Requirements({"GW_INGEST_0066"})
    public void advanceIngest(long id, UUID attempt, String stage) {
        jdbc.sql("UPDATE marketplaces SET ingest_stage = :stage::marketplace_ingest_stage,"
                        + " ingest_heartbeat_at = now() WHERE id = :id AND ingest_attempt = :attempt")
                .param("stage", stage)
                .param("attempt", attempt)
                .param("id", id)
                .update();
    }

    /** Renews the heartbeat of the attempts this replica owns (GW_INGEST_0067). */
    @Requirements({"GW_INGEST_0067"})
    public void heartbeat(Collection<UUID> attempts) {
        if (attempts.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE marketplaces SET ingest_heartbeat_at = now() WHERE ingest_attempt IN (:attempts)")
                .param("attempts", attempts)
                .update();
    }

    /**
     * A marketplace's running and last finished ingest, read in one statement so the two halves
     * agree (GW_INGEST_0066). Interrupted is decided by the database clock, as the claim is.
     */
    @Requirements({"GW_INGEST_0066", "GW_INGEST_0067"})
    public Optional<IngestRecord> ingestRecord(String name, Duration stale) {
        return jdbc.sql("SELECT m.name, m.ingest_attempt, m.ingest_stage::text AS ingest_stage, m.ingest_started_at,"
                        + " m.ingest_heartbeat_at < now() - make_interval(secs => :stale) AS interrupted,"
                        + " m.last_ingest_at, m.last_ingest_outcome::text AS last_ingest_outcome,"
                        + " m.last_ingest_reason, m.last_ingest_failure::text AS last_ingest_failure,"
                        + " m.last_ingest_snapshot_id, s.state::text AS last_ingest_snapshot_state"
                        + " FROM marketplaces m LEFT JOIN snapshots s ON s.id = m.last_ingest_snapshot_id"
                        + " WHERE m.name = :name AND m.deleted_at IS NULL")
                .param("name", name)
                .param("stale", (double) stale.toSeconds())
                .query(IngestRecord.class)
                .optional();
    }

    /** The row behind a marketplace's ingest status; the running half is all null when none runs. */
    public record IngestRecord(
            String name,
            UUID ingestAttempt,
            String ingestStage,
            Instant ingestStartedAt,
            Boolean interrupted,
            Instant lastIngestAt,
            String lastIngestOutcome,
            String lastIngestReason,
            String lastIngestFailure,
            Long lastIngestSnapshotId,
            String lastIngestSnapshotState) {}

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
