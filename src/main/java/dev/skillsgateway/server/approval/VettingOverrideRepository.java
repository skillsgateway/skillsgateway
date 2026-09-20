package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.persistence.SqlArrays;
import io.github.reqstool.annotations.Requirements;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link VettingOverrideRecord} (GW_VETTING_0028). One row per snapshot, replaced if the
 * snapshot is re-approved over a failure again — the audit ledger keeps the full history, this is
 * the current standing marker that makes the override fail-loud on the snapshot's vetting surface.
 */
@Repository
public class VettingOverrideRepository {

    private final JdbcClient jdbc;

    public VettingOverrideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_VETTING_0028"})
    public VettingOverrideRecord record(
            long snapshotId,
            String reason,
            List<String> blockingVetters,
            List<String> uncoveredFindings,
            String overriddenBy) {
        return jdbc.sql("INSERT INTO snapshot_vetting_overrides"
                        + " (snapshot_id, reason, blocking_vetters, uncovered_findings, overridden_by, overridden_at)"
                        + " VALUES (:snapshotId, :reason, :blockingVetters::text[], :uncoveredFindings::text[], :overriddenBy, :now)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET reason = :reason,"
                        + " blocking_vetters = :blockingVetters::text[], uncovered_findings = :uncoveredFindings::text[],"
                        + " overridden_by = :overriddenBy, overridden_at = :now"
                        + " RETURNING *")
                .param("snapshotId", snapshotId)
                .param("reason", reason)
                .param("blockingVetters", SqlArrays.literal(blockingVetters))
                .param("uncoveredFindings", SqlArrays.literal(uncoveredFindings))
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
                SqlArrays.read(rs, "blocking_vetters"),
                SqlArrays.read(rs, "uncovered_findings"),
                rs.getString("overridden_by"),
                overriddenAt == null ? null : overriddenAt.toInstant());
    }
}
