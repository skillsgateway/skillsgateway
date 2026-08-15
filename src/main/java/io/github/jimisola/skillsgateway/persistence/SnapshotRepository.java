package io.github.jimisola.skillsgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SnapshotRepository {

    private final JdbcClient jdbc;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot create(long marketplaceId, String sha, String state, String violation) {
        return jdbc.sql("INSERT INTO snapshots (marketplace_id, sha, state, violation, created_at)"
                        + " VALUES (:marketplaceId, :sha, :state, :violation, :now) RETURNING *")
                .param("marketplaceId", marketplaceId)
                .param("sha", sha)
                .param("state", state)
                .param("violation", violation)
                .param("now", OffsetDateTime.now())
                .query(SnapshotRepository::map)
                .single();
    }

    public Optional<Snapshot> findById(long id) {
        return jdbc.sql("SELECT * FROM snapshots WHERE id = :id")
                .param("id", id)
                .query(SnapshotRepository::map)
                .optional();
    }

    public Optional<Snapshot> findByMarketplaceAndSha(long marketplaceId, String sha) {
        return jdbc.sql("SELECT * FROM snapshots WHERE marketplace_id = :marketplaceId AND sha = :sha")
                .param("marketplaceId", marketplaceId)
                .param("sha", sha)
                .query(SnapshotRepository::map)
                .optional();
    }

    public List<Snapshot> listByMarketplace(long marketplaceId) {
        return jdbc.sql("SELECT * FROM snapshots WHERE marketplace_id = :marketplaceId ORDER BY id")
                .param("marketplaceId", marketplaceId)
                .query(SnapshotRepository::map)
                .list();
    }

    /**
     * Reviewer decision with the state machine enforced: only held snapshots can be decided, so a
     * rejected (policy-violating) snapshot can never become approved.
     */
    @Transactional
    public Snapshot decide(long id, String newState, String reviewer) {
        Snapshot snapshot = findById(id).orElseThrow(() -> new SnapshotNotFoundException(id));
        if (!Snapshot.HELD.equals(snapshot.state())) {
            throw new IllegalStateException("invalid transition %s -> %s".formatted(snapshot.state(), newState));
        }
        if (!Snapshot.APPROVED.equals(newState) && !Snapshot.REJECTED.equals(newState)) {
            throw new IllegalStateException("invalid target state " + newState);
        }
        return jdbc.sql("UPDATE snapshots SET state = :state, decided_by = :reviewer, decided_at = :now"
                        + " WHERE id = :id RETURNING *")
                .param("state", newState)
                .param("reviewer", reviewer)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(SnapshotRepository::map)
                .single();
    }

    /**
     * Snapshots a retention policy would delete for one marketplace, each with the criterion that
     * selected it (GW_0031).
     *
     * <p>Three guarantees are in the SQL rather than in the caller, so no code path can lose them:
     * an {@code approved} snapshot is never returned (GW_0033), an already-deleted snapshot is
     * never returned twice, and a snapshot served through the facade since {@code idleCutoff} is
     * vetoed regardless of which criterion matched it.
     */
    public List<Candidate> candidates(
            long marketplaceId,
            boolean heldEnabled,
            Instant heldCutoff,
            boolean supersededEnabled,
            Instant supersededCutoff,
            Instant idleCutoff,
            int limit) {
        String heldMatch = "(:heldEnabled AND s.state = 'held' AND s.created_at < :heldCutoff)";
        String supersededMatch = "(:supersededEnabled AND s.created_at < :supersededCutoff AND EXISTS ("
                + " SELECT 1 FROM snapshots newer WHERE newer.marketplace_id = s.marketplace_id"
                + " AND newer.id > s.id AND newer.state = 'approved'))";
        return jdbc.sql("SELECT s.*, CASE WHEN " + heldMatch + " THEN '" + Candidate.HELD_TOO_LONG + "'"
                        + " ELSE '" + Candidate.SUPERSEDED + "' END AS reason FROM snapshots s"
                        + " WHERE s.marketplace_id = :marketplaceId"
                        + " AND s.state <> 'approved'"
                        + " AND s.deleted_at IS NULL"
                        + " AND (" + heldMatch + " OR " + supersededMatch + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM fetch_log f WHERE f.sha = s.sha"
                        + " AND f.source <> 'admin' AND f.ts > :idleCutoff)"
                        + " ORDER BY s.id LIMIT :limit")
                .param("marketplaceId", marketplaceId)
                .param("heldEnabled", heldEnabled)
                .param("heldCutoff", heldCutoff.atOffset(ZoneOffset.UTC))
                .param("supersededEnabled", supersededEnabled)
                .param("supersededCutoff", supersededCutoff.atOffset(ZoneOffset.UTC))
                .param("idleCutoff", idleCutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, rowNum) -> new Candidate(map(rs, rowNum), rs.getString("reason")))
                .list();
    }

    /**
     * Marks the snapshot deleted, restorable until {@code purgeAfter} (GW_0032). Approved snapshots
     * are excluded in the statement itself, so this cannot delete served content whatever the
     * caller believes (GW_0033); an already-deleted snapshot keeps its original marks.
     */
    public Optional<Snapshot> softDelete(long id, String reason, Instant purgeAfter) {
        return jdbc.sql("UPDATE snapshots SET deleted_at = :now, deleted_reason = :reason,"
                        + " purge_after = :purgeAfter"
                        + " WHERE id = :id AND state <> 'approved' AND deleted_at IS NULL RETURNING *")
                .param("now", OffsetDateTime.now())
                .param("reason", reason)
                .param("purgeAfter", purgeAfter.atOffset(ZoneOffset.UTC))
                .param("id", id)
                .query(SnapshotRepository::map)
                .optional();
    }

    /** Clears the deletion marks; the vetting state was never touched, so nothing else changes. */
    public Optional<Snapshot> restore(long id) {
        return jdbc.sql("UPDATE snapshots SET deleted_at = NULL, deleted_reason = NULL, purge_after = NULL"
                        + " WHERE id = :id AND deleted_at IS NOT NULL RETURNING *")
                .param("id", id)
                .query(SnapshotRepository::map)
                .optional();
    }

    /** Soft-deleted snapshots whose restore window has elapsed: the compaction queue (GW_0034). */
    public List<Snapshot> duePurge(Instant now, int limit) {
        return jdbc.sql("SELECT * FROM snapshots WHERE deleted_at IS NOT NULL AND purge_after <= :now"
                        + " AND state <> 'approved' ORDER BY id LIMIT :limit")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(SnapshotRepository::map)
                .list();
    }

    /** Permanent removal of the record; the caller has already removed the git storage. */
    public boolean purge(long id) {
        return jdbc.sql("DELETE FROM snapshots WHERE id = :id AND deleted_at IS NOT NULL AND state <> 'approved'")
                        .param("id", id)
                        .update()
                > 0;
    }

    /** A snapshot a retention policy selected, and the criterion that selected it. */
    public record Candidate(Snapshot snapshot, String reason) {

        public static final String HELD_TOO_LONG = "held-too-long";
        public static final String SUPERSEDED = "superseded";
    }

    static Snapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new Snapshot(
                rs.getLong("id"),
                rs.getLong("marketplace_id"),
                rs.getString("sha"),
                rs.getString("state"),
                rs.getString("violation"),
                MarketplaceRepository.instant(rs, "created_at"),
                rs.getString("decided_by"),
                MarketplaceRepository.instant(rs, "decided_at"),
                MarketplaceRepository.instant(rs, "deleted_at"),
                rs.getString("deleted_reason"),
                MarketplaceRepository.instant(rs, "purge_after"));
    }
}
