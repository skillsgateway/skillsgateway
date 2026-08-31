package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
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

    /**
     * The states retention may remove, spelled out rather than expressed as "not approved"
     * (GW_0033, GW_0050). Every future state must be added here on purpose to become deletable;
     * a negation would have let {@code revoked} in without anyone deciding that it should be.
     */
    private static final String DELETABLE_STATES = "('held', 'rejected', 'revoked')";

    private final JdbcClient jdbc;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a snapshot together with the identity that triggered its ingestion (GW_0096). The
     * actor is a column rather than a ledger lookup because the four-eyes rule reads it as an
     * authorization input, and null is a legitimate value: a snapshot whose actor was never
     * recorded conflicts with nobody.
     */
    @Requirements({"GW_0096", "GW_0125"})
    public Snapshot create(long marketplaceId, String sha, String state, String violation, String ingestedBy) {
        return jdbc.sql("INSERT INTO snapshots (marketplace_id, sha, state, violation, created_at, ingested_by)"
                        + " VALUES (:marketplaceId, :sha, :state::snapshot_state, :violation, :now, :ingestedBy)"
                        + " RETURNING *")
                .param("marketplaceId", marketplaceId)
                .param("ingestedBy", ingestedBy)
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
     * Reviewer decision with the state machine enforced: {@code held -> approved | rejected} and
     * {@code revoked -> approved | rejected}. A rejected (policy-violating) snapshot can never
     * become approved, and an approved snapshot is never re-decided in place — only revocation
     * moves it, and only {@link #revoke} does that.
     *
     * <p>A revoked snapshot is decidable again on purpose (GW_0050): retraction must be reversible
     * by a person, and the only route back is this method — a fresh decision with a fresh reviewer
     * and timestamp, behind the same effective-vetting gate every approval passes. The revocation
     * stamps are cleared by the transition, so a re-published snapshot never carries a revocation
     * marker that no longer holds; what it was revoked for stays in the ledger.
     */
    @Requirements({"GW_0125"})
    @Transactional
    public Snapshot decide(long id, String newState, String reviewer) {
        Snapshot snapshot = findById(id).orElseThrow(() -> new SnapshotNotFoundException(id));
        if (!snapshot.decidable()) {
            throw new IllegalStateException("invalid transition %s -> %s".formatted(snapshot.state(), newState));
        }
        if (!Snapshot.APPROVED.equals(newState) && !Snapshot.REJECTED.equals(newState)) {
            throw new IllegalStateException("invalid target state " + newState);
        }
        return jdbc.sql(
                        "UPDATE snapshots SET state = :state::snapshot_state, decided_by = :reviewer, decided_at = :now,"
                                + " revoked_at = NULL, revoked_by = NULL, violation = NULL"
                                + " WHERE id = :id RETURNING *")
                .param("state", newState)
                .param("reviewer", reviewer)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(SnapshotRepository::map)
                .single();
    }

    /**
     * Puts a row back exactly as it was before an approval that could not be published (GW_0133).
     *
     * <p>{@code decide} commits before publication, and it also clears {@code revoked_at},
     * {@code revoked_by} and {@code violation} — so an approval that then fails to publish leaves a
     * row claiming a publication that never happened, and, when the snapshot had been revoked
     * before, one whose revocation stamps have been erased while the ledger still carries them.
     * This is the compensation: every field {@code decide} touched is restored from the row as it
     * was read before the decision.
     *
     * <p>Deliberately not {@link #revoke(long, String, String)}. That is the retroactive-quarantine
     * transition and stamps a revoker and a violation; a publication that failed is not a policy
     * revocation, and recording it as one would put a fabricated revocation in front of an auditor.
     *
     * <p>The {@code state = 'approved'} predicate is in the statement, so a concurrent revocation or
     * a second attempt cannot be clobbered by this repair.
     *
     * @param before the row as it was before {@code decide} was called
     * @return the restored snapshot, or empty when the row was no longer {@code approved} — which
     *     means something else decided it in the meantime and the caller must not claim to have
     *     repaired anything
     */
    @Requirements({"GW_0133"})
    @Transactional
    public Optional<Snapshot> undecide(Snapshot before) {
        return jdbc.sql("UPDATE snapshots SET state = :state::snapshot_state, violation = :violation,"
                        + " revoked_at = :revokedAt, revoked_by = :revokedBy,"
                        + " decided_by = :decidedBy, decided_at = :decidedAt"
                        + " WHERE id = :id AND state = :approved::snapshot_state RETURNING *")
                .param("state", before.state())
                .param("violation", before.violation())
                .param("revokedAt", at(before.revokedAt()))
                .param("revokedBy", before.revokedBy())
                .param("decidedBy", before.decidedBy())
                .param("decidedAt", at(before.decidedAt()))
                .param("id", before.id())
                .param("approved", Snapshot.APPROVED)
                .query(SnapshotRepository::map)
                .optional();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Retroactive quarantine (GW_0050): the one transition out of {@code approved}. The
     * {@code state = 'approved'} predicate is in the statement rather than in the caller, so a
     * concurrent revocation or a second sweep pass cannot revoke the same snapshot twice, and
     * nothing that is not currently approved can be revoked at all.
     *
     * @return the revoked snapshot, or empty when it was not approved (already revoked, or never
     *     approved) — which the caller must treat as "someone else got there first", not an error
     */
    @Requirements({"GW_0125"})
    public Optional<Snapshot> revoke(long id, String actor, String violation) {
        return jdbc.sql("UPDATE snapshots SET state = :state::snapshot_state, revoked_at = :now, revoked_by = :actor,"
                        + " violation = :violation WHERE id = :id AND state = :approved::snapshot_state RETURNING *")
                .param("state", Snapshot.REVOKED)
                .param("now", OffsetDateTime.now())
                .param("actor", actor)
                .param("violation", violation)
                .param("id", id)
                .param("approved", Snapshot.APPROVED)
                .query(SnapshotRepository::map)
                .optional();
    }

    /**
     * The continuous re-vetting queue (GW_0049): live approved snapshots whose most recent chain
     * run is older than {@code cutoff}, oldest first, and a snapshot that has never been vetted
     * before all of them.
     *
     * <p>Oldest-run-first is what makes a bounded batch fair: every approved snapshot is reached in
     * turn, so no snapshot can be starved by a large estate, and no tick re-vets the whole estate.
     * Only {@code approved} snapshots are returned — nothing else is being served, so nothing else
     * can be retroactively quarantined — and soft-deleted ones are excluded because re-vetting
     * content that is on its way out would spend the budget on the wrong snapshots.
     */
    public List<Snapshot> dueRevet(Instant cutoff, int limit) {
        return jdbc.sql("SELECT s.* FROM snapshots s"
                        + " LEFT JOIN LATERAL (SELECT MAX(r.started_at) AS last_run FROM vetting_runs r"
                        + "   WHERE r.snapshot_id = s.id) latest ON TRUE"
                        + " WHERE s.state = :approved::snapshot_state AND s.deleted_at IS NULL"
                        + " AND (latest.last_run IS NULL OR latest.last_run < :cutoff)"
                        + " ORDER BY latest.last_run ASC NULLS FIRST, s.id LIMIT :limit")
                .param("approved", Snapshot.APPROVED)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(SnapshotRepository::map)
                .list();
    }

    /** Every live approved snapshot of one marketplace: what a manual marketplace re-vet covers. */
    public List<Snapshot> approvedByMarketplace(long marketplaceId) {
        return jdbc.sql("SELECT * FROM snapshots WHERE marketplace_id = :marketplaceId"
                        + " AND state = :approved::snapshot_state AND deleted_at IS NULL ORDER BY id")
                .param("marketplaceId", marketplaceId)
                .param("approved", Snapshot.APPROVED)
                .query(SnapshotRepository::map)
                .list();
    }

    /**
     * Snapshots a retention policy would delete for one marketplace, each with the criterion that
     * selected it (GW_0031).
     *
     * <p>Three guarantees are in the SQL rather than in the caller, so no code path can lose them:
     * an {@code approved} snapshot is never returned (GW_0033), an already-deleted snapshot is
     * never returned twice, and a snapshot served through the facade since {@code idleCutoff} is
     * vetoed regardless of which criterion matched it.
     *
     * <p>The idle veto matches the fetch on marketplace <em>and</em> commit, not on the commit
     * alone. A SHA is not unique across marketplaces — a fork, a mirror, or the same upstream
     * registered twice all carry it — and the facade serves per marketplace, so a fetch of one
     * marketplace says nothing about whether another marketplace's identically-pinned snapshot is
     * still in use. Vetoing on the SHA alone let any marketplace's traffic hold every other
     * marketplace's snapshots in quarantine indefinitely.
     *
     * <p>Deletable states are named explicitly rather than written as "not approved" (GW_0050):
     * {@code revoked} joined the state machine after retention did, and a categorical guard phrased
     * as a negation would have admitted it silently. It <em>is</em> admitted — a revoked snapshot is
     * not served, so removing it destroys nothing anyone can fetch — but only through the
     * {@code superseded} criterion, never {@code held-too-long}, which names {@code held} itself.
     * The idle veto still applies to it, which is what keeps a snapshot around while the consumers
     * that fetched it before the revocation are still recent.
     */
    public List<Candidate> candidates(
            long marketplaceId,
            String marketplaceName,
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
                        + " AND s.state IN " + DELETABLE_STATES
                        + " AND s.deleted_at IS NULL"
                        + " AND (" + heldMatch + " OR " + supersededMatch + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM fetch_log f WHERE f.sha = s.sha"
                        + " AND f.marketplace = :marketplaceName"
                        + " AND f.source <> 'admin' AND f.ts > :idleCutoff)"
                        + " ORDER BY s.id LIMIT :limit")
                .param("marketplaceId", marketplaceId)
                .param("marketplaceName", marketplaceName)
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
                        + " WHERE id = :id AND state IN " + DELETABLE_STATES
                        + " AND deleted_at IS NULL RETURNING *")
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
                        + " AND state IN " + DELETABLE_STATES + " ORDER BY id LIMIT :limit")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(SnapshotRepository::map)
                .list();
    }

    /** Permanent removal of the record; the caller has already removed the git storage. */
    public boolean purge(long id) {
        return jdbc.sql("DELETE FROM snapshots WHERE id = :id AND deleted_at IS NOT NULL" + " AND state IN "
                                + DELETABLE_STATES)
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
                rs.getString("ingested_by"),
                rs.getString("decided_by"),
                MarketplaceRepository.instant(rs, "decided_at"),
                MarketplaceRepository.instant(rs, "revoked_at"),
                rs.getString("revoked_by"),
                MarketplaceRepository.instant(rs, "deleted_at"),
                rs.getString("deleted_reason"),
                MarketplaceRepository.instant(rs, "purge_after"));
    }
}
