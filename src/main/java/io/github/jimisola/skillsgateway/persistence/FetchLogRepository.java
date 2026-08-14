package io.github.jimisola.skillsgateway.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only fetch ledger: this repository intentionally offers no update or delete. */
@Repository
public class FetchLogRepository {

    private final JdbcClient jdbc;

    public FetchLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String source, String principal, String marketplace, String event, String ref, String sha) {
        jdbc.sql("INSERT INTO fetch_log (ts, source, principal, marketplace, event, ref, sha)"
                        + " VALUES (:now, :source, :principal, :marketplace, :event, :ref, :sha)")
                .param("now", OffsetDateTime.now())
                .param("source", source)
                .param("principal", principal)
                .param("marketplace", marketplace)
                .param("event", event)
                .param("ref", ref)
                .param("sha", sha)
                .update();
    }

    public List<Map<String, Object>> list() {
        return jdbc.sql("SELECT * FROM fetch_log ORDER BY id").query().listOfRows();
    }

    /**
     * The page of ledger entries an export consumer sees next: everything after its cursor, in
     * ledger order, bounded by {@code limit} (GW_0027, GW_0028).
     *
     * <p>{@code cutoff} excludes entries younger than the commit-settling lag. {@code id} comes
     * from a {@code BIGSERIAL}, which is assigned before commit, so without the cutoff a reader
     * could advance past a concurrently-inserted lower id and never see it again.
     */
    public List<AuditEntry> entriesAfter(long cursor, Instant cutoff, int limit) {
        return jdbc.sql("SELECT * FROM fetch_log WHERE id > :cursor AND ts <= :cutoff ORDER BY id LIMIT :limit")
                .param("cursor", cursor)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(FetchLogRepository::map)
                .list();
    }

    /**
     * The sequence a reader would reach after consuming at most {@code limit} entries past
     * {@code cursor} — the resume cursor, known before a single entry has been streamed. Returns
     * {@code cursor} unchanged when nothing is available.
     */
    public long cursorAfter(long cursor, Instant cutoff, int limit) {
        return jdbc.sql("SELECT COALESCE(MAX(id), :cursor) FROM (SELECT id FROM fetch_log"
                        + " WHERE id > :cursor AND ts <= :cutoff ORDER BY id LIMIT :limit) page")
                .param("cursor", cursor)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(Long.class)
                .single();
    }

    /** Highest ledger sequence written so far; 0 when the ledger is empty. */
    public long maxId() {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM fetch_log")
                .query(Long.class)
                .single();
    }

    /** One ledger entry as exported: the ledger sequence is the consumer's de-duplication key. */
    @Schema(description = "One append-only audit ledger entry")
    public record AuditEntry(
            @Schema(description = "Ledger sequence; the export cursor and de-duplication key")
            long id,

            @Schema(description = "When the entry was appended, ISO-8601")
            String ts,

            @Schema(description = "Client address for a facade fetch, or 'admin' for an administrative action")
            String source,

            @Schema(description = "Authenticated identity, when there was one")
            String principal,

            @Schema(description = "Marketplace the entry concerns, or '-'")
            String marketplace,

            @Schema(description = "What happened") String event,
            @Schema(description = "Served ref, for fetches") String ref,

            @Schema(description = "Commit SHA, when the entry concerns one")
            String sha) {}

    private static AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                // Rendered here rather than as a java.time value: the exported line is serialized by
                // a plain ObjectMapper, and ISO-8601 text is what a SIEM ingests anyway.
                String.valueOf(MarketplaceRepository.instant(rs, "ts")),
                rs.getString("source"),
                rs.getString("principal"),
                rs.getString("marketplace"),
                rs.getString("event"),
                rs.getString("ref"),
                rs.getString("sha"));
    }

    public record FetchRecord(long id, Instant ts, String source, String principal, String marketplace, String event) {}
}
