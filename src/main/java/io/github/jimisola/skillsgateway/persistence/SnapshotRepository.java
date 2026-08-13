package io.github.jimisola.skillsgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

    static Snapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new Snapshot(
                rs.getLong("id"),
                rs.getLong("marketplace_id"),
                rs.getString("sha"),
                rs.getString("state"),
                rs.getString("violation"),
                MarketplaceRepository.instant(rs, "created_at"),
                rs.getString("decided_by"),
                MarketplaceRepository.instant(rs, "decided_at"));
    }
}
