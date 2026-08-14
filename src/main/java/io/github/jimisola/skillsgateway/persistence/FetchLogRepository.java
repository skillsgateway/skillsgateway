package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
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

    public record FetchRecord(long id, Instant ts, String source, String principal, String marketplace, String event) {}
}
