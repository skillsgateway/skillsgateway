package dev.skillsgateway.server.persistence;

import io.github.reqstool.annotations.Requirements;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditSinkRepository {

    private final JdbcClient jdbc;

    public AuditSinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Requirements({"GW_FACADE_0009"})
    public AuditSink create(String name, String kind, long subscriberId, long cursorPosition, int batchSize) {
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.sql("INSERT INTO audit_sinks"
                        + " (name, kind, subscriber_id, cursor_position, batch_size, enabled, created_at, updated_at)"
                        + " VALUES (:name, :kind::audit_sink_kind, :subscriberId, :cursor, :batchSize, TRUE, :now, :now)"
                        + " RETURNING *")
                .param("name", name)
                .param("kind", kind)
                .param("subscriberId", subscriberId)
                .param("cursor", cursorPosition)
                .param("batchSize", batchSize)
                .param("now", now)
                .query(AuditSink.class)
                .single();
    }

    public List<AuditSink> list() {
        return jdbc.sql("SELECT * FROM audit_sinks ORDER BY id")
                .query(AuditSink.class)
                .list();
    }

    public List<AuditSink> listEnabled() {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE enabled ORDER BY id")
                .query(AuditSink.class)
                .list();
    }

    public Optional<AuditSink> findById(long id) {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE id = :id")
                .param("id", id)
                .query(AuditSink.class)
                .optional();
    }

    public Optional<AuditSink> findByName(String name) {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE name = :name")
                .param("name", name)
                .query(AuditSink.class)
                .optional();
    }

    /**
     * Advances the cursor only if it is still where the caller read it. The export path uses this
     * one, because its write is a <em>consequence</em> of a value it read: an unconditional write
     * there overwrites whatever happened in between, and what happens in between is an operator's
     * replay rewind (GW_AUDIT_0005), silently undone by the next export pass while the replay
     * reports success and delivers nothing.
     *
     * <p>It does not deduplicate a delivery. Two exporters that both read {@code expected} have
     * both enqueued the same batch before either writes; the sweep lease is what stops there being
     * two exporters. This stops the cursor being <em>clobbered</em>, which is a different and
     * smaller claim.
     */
    public Optional<AuditSink> advanceCursor(long id, long cursorPosition, long expected) {
        return jdbc.sql("UPDATE audit_sinks SET cursor_position = :cursor, updated_at = :now"
                        + " WHERE id = :id AND cursor_position = :expected RETURNING *")
                .param("cursor", cursorPosition)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .param("expected", expected)
                .query(AuditSink.class)
                .optional();
    }

    /**
     * Moves the sink's cursor unconditionally. This is the replay rewind (GW_AUDIT_0005), where
     * overriding the current value <em>is</em> the operation, so there is nothing to compare
     * against. Advancing after an export goes through {@link #advanceCursor(long, long, long)}.
     */
    public Optional<AuditSink> updateCursor(long id, long cursorPosition) {
        return jdbc.sql("UPDATE audit_sinks SET cursor_position = :cursor, updated_at = :now"
                        + " WHERE id = :id RETURNING *")
                .param("cursor", cursorPosition)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(AuditSink.class)
                .optional();
    }

    /** Converges a sink's batch size to a declared state (GW_ESTATE_0004); the cursor is never touched here. */
    public Optional<AuditSink> updateBatchSize(long id, int batchSize) {
        return jdbc.sql("UPDATE audit_sinks SET batch_size = :batchSize, updated_at = :now"
                        + " WHERE id = :id RETURNING *")
                .param("batchSize", batchSize)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(AuditSink.class)
                .optional();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM audit_sinks WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }
}
