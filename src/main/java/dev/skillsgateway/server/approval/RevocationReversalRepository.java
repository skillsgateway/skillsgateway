package dev.skillsgateway.server.approval;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link RevocationReversalRecord} (GW_APPROVAL_0017). One row per snapshot,
 * replaced if the same snapshot is withdrawn and put back again — the audit ledger keeps the full
 * history, this is the current standing marker that keeps the episode visible wherever the
 * snapshot's vetting is shown.
 *
 * <p>Mirrors {@link VettingOverrideRepository} deliberately: both exist because an approval that
 * overrode something must never look like one that had nothing to override.
 */
@Repository
public class RevocationReversalRepository {

    private final JdbcClient jdbc;

    public RevocationReversalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_APPROVAL_0017"})
    public RevocationReversalRecord record(
            long snapshotId,
            String revocationReason,
            String revokedBy,
            OffsetDateTime revokedAt,
            String reason,
            String reversedBy) {
        return jdbc.sql("INSERT INTO snapshot_revocation_reversals"
                        + " (snapshot_id, revocation_reason, revoked_by, revoked_at, reason, reversed_by, reversed_at)"
                        + " VALUES (:snapshotId, :revocationReason, :revokedBy, :revokedAt, :reason, :reversedBy, :now)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET revocation_reason = :revocationReason,"
                        + " revoked_by = :revokedBy, revoked_at = :revokedAt, reason = :reason,"
                        + " reversed_by = :reversedBy, reversed_at = :now"
                        + " RETURNING *")
                .param("snapshotId", snapshotId)
                .param("revocationReason", revocationReason)
                .param("revokedBy", revokedBy)
                .param("revokedAt", revokedAt)
                .param("reason", reason)
                .param("reversedBy", reversedBy)
                .param("now", OffsetDateTime.now())
                .query(RevocationReversalRepository::map)
                .single();
    }

    public Optional<RevocationReversalRecord> findBySnapshot(long snapshotId) {
        return jdbc.sql("SELECT * FROM snapshot_revocation_reversals WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId)
                .query(RevocationReversalRepository::map)
                .optional();
    }

    private static RevocationReversalRecord map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
        OffsetDateTime reversedAt = rs.getObject("reversed_at", OffsetDateTime.class);
        return new RevocationReversalRecord(
                rs.getLong("id"),
                rs.getLong("snapshot_id"),
                rs.getString("revocation_reason"),
                rs.getString("revoked_by"),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("reason"),
                rs.getString("reversed_by"),
                reversedAt == null ? null : reversedAt.toInstant());
    }
}
