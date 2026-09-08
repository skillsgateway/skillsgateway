package dev.skillsgateway.server.persistence;

import dev.skillsgateway.server.ingestion.SnapshotClosure;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The persisted closure of a composite snapshot (GW_INGEST_0030). Insert and read only: a closure is
 * never updated, and it is removed by the snapshot's purge through the cascade, so there is no
 * delete here either.
 */
@Repository
public class SnapshotClosureRepository {

    private final JdbcClient jdbc;

    public SnapshotClosureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A recorded closure: the value, plus the row identity and when it was written. */
    @Schema(description = "The resolved closure of external plugin sources recorded with a snapshot")
    public record Closure(
            @Schema(description = "Closure id") long id,

            @Schema(description = "The snapshot this closure belongs to")
            long snapshotId,

            @Schema(description = "SHA-256 over the closure's content; equal for equal closures wherever they occur")
            String digest,

            @Schema(description = "The commit ingested from upstream")
            String upstreamSha,

            @Schema(description = "Identity of the rewrite implementation")
            String transformerVersion,

            @Schema(description = "When the closure was recorded, which is when the snapshot was")
            Instant createdAt,

            @Schema(description = "One member per resolved external plugin")
            List<SnapshotClosure.Member> members) {

        public SnapshotClosure value() {
            return new SnapshotClosure(upstreamSha, transformerVersion, members);
        }
    }

    /**
     * Writes the closure and its members. Called inside the snapshot insert's transaction — see
     * {@link SnapshotRepository#create(long, String, String, String, String, String,
     * SnapshotClosure)} — so a snapshot and its closure exist together or not at all.
     */
    @Requirements({"GW_INGEST_0030"})
    public Closure record(long snapshotId, SnapshotClosure closure) {
        long id = jdbc.sql("INSERT INTO snapshot_closures (snapshot_id, digest, upstream_sha, transformer_version,"
                        + " created_at) VALUES (:snapshotId, :digest, :upstreamSha, :transformerVersion, :now)"
                        + " RETURNING id")
                .param("snapshotId", snapshotId)
                .param("digest", closure.digest())
                .param("upstreamSha", closure.upstreamSha())
                .param("transformerVersion", closure.transformerVersion())
                .param("now", OffsetDateTime.now())
                .query(Long.class)
                .single();
        for (SnapshotClosure.Member member : closure.members()) {
            jdbc.sql("INSERT INTO snapshot_closure_members (closure_id, plugin_name, source_type, declared_source,"
                            + " declared_ref, declared_sha, clone_url, resolved_sha, tree_sha, graft_path,"
                            + " object_count, inflated_bytes) VALUES (:closureId, :pluginName, :sourceType,"
                            + " :declaredSource, :declaredRef, :declaredSha, :cloneUrl, :resolvedSha, :treeSha,"
                            + " :graftPath, :objectCount, :inflatedBytes)")
                    .param("closureId", id)
                    .param("pluginName", member.pluginName())
                    .param("sourceType", member.sourceType())
                    .param("declaredSource", member.declaredSource())
                    .param("declaredRef", member.declaredRef())
                    .param("declaredSha", member.declaredSha())
                    .param("cloneUrl", member.cloneUrl())
                    .param("resolvedSha", member.resolvedSha())
                    .param("treeSha", member.treeSha())
                    .param("graftPath", member.graftPath())
                    .param("objectCount", member.objectCount())
                    .param("inflatedBytes", member.inflatedBytes())
                    .update();
        }
        return findBySnapshot(snapshotId)
                .orElseThrow(() -> new IllegalStateException("closure %d vanished after insert".formatted(id)));
    }

    /** The snapshot's closure, or empty for a snapshot that resolved nothing. */
    @Requirements({"GW_INGEST_0030"})
    public Optional<Closure> findBySnapshot(long snapshotId) {
        return jdbc.sql("SELECT * FROM snapshot_closures WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId)
                .query((rs, rowNum) -> new Closure(
                        rs.getLong("id"),
                        rs.getLong("snapshot_id"),
                        rs.getString("digest"),
                        rs.getString("upstream_sha"),
                        rs.getString("transformer_version"),
                        Timestamps.instant(rs, "created_at"),
                        members(rs.getLong("id"))))
                .optional();
    }

    /**
     * The blast-radius query: every snapshot whose closure contains {@code cloneUrl}, at
     * {@code resolvedSha} when one is given and at any commit otherwise. What a compromised
     * external repository turns into: a list of snapshots to re-vet.
     */
    @Requirements({"GW_INGEST_0030"})
    public List<Long> snapshotsContaining(String cloneUrl, String resolvedSha) {
        return jdbc.sql("SELECT DISTINCT c.snapshot_id FROM snapshot_closure_members m"
                        + " JOIN snapshot_closures c ON c.id = m.closure_id"
                        + " WHERE m.clone_url = :cloneUrl"
                        + " AND (:resolvedSha::text IS NULL OR m.resolved_sha = :resolvedSha)"
                        + " ORDER BY c.snapshot_id")
                .param("cloneUrl", cloneUrl)
                .param("resolvedSha", resolvedSha)
                .query(Long.class)
                .list();
    }

    private List<SnapshotClosure.Member> members(long closureId) {
        return jdbc.sql("SELECT * FROM snapshot_closure_members WHERE closure_id = :closureId ORDER BY graft_path")
                .param("closureId", closureId)
                .query(SnapshotClosure.Member.class)
                .list();
    }
}
