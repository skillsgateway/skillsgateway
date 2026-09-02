package dev.skillsgateway.server.approval;

import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link VettingOverrideRecord} (GW_0148). One row per snapshot, replaced if the
 * snapshot is re-approved over a failure again — the audit ledger keeps the full history, this is
 * the current standing marker that makes the override fail-loud on the snapshot's vetting surface.
 */
@Repository
public class VettingOverrideRepository {

    private final JdbcClient jdbc;

    public VettingOverrideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_0148"})
    public VettingOverrideRecord record(
            long snapshotId, String reason, String blockingConnectors, String uncoveredFindings, String overriddenBy) {
        return jdbc.sql("INSERT INTO snapshot_vetting_overrides"
                        + " (snapshot_id, reason, blocking_connectors, uncovered_findings, overridden_by, overridden_at)"
                        + " VALUES (:snapshotId, :reason, :blockingConnectors, :uncoveredFindings, :overriddenBy, :now)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET reason = :reason,"
                        + " blocking_connectors = :blockingConnectors, uncovered_findings = :uncoveredFindings,"
                        + " overridden_by = :overriddenBy, overridden_at = :now"
                        + " RETURNING *")
                .param("snapshotId", snapshotId)
                .param("reason", reason)
                .param("blockingConnectors", blockingConnectors)
                .param("uncoveredFindings", uncoveredFindings)
                .param("overriddenBy", overriddenBy)
                .param("now", OffsetDateTime.now())
                .query(VettingOverrideRepository::map)
                .single();
    }

    public Optional<VettingOverrideRecord> findBySnapshot(long snapshotId) {
        return jdbc.sql("SELECT * FROM snapshot_vetting_overrides WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId)
                .query(VettingOverrideRepository::map)
                .optional();
    }

    private static VettingOverrideRecord map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime overriddenAt = rs.getObject("overridden_at", OffsetDateTime.class);
        return new VettingOverrideRecord(
                rs.getLong("id"),
                rs.getLong("snapshot_id"),
                rs.getString("reason"),
                rs.getString("blocking_connectors"),
                rs.getString("uncovered_findings"),
                rs.getString("overridden_by"),
                overriddenAt == null ? null : overriddenAt.toInstant());
    }
}
