package dev.skillsgateway.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public AuditSink create(String name, String kind, long subscriberId, long cursorPosition, int batchSize) {
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.sql("INSERT INTO audit_sinks"
                        + " (name, kind, subscriber_id, cursor_position, batch_size, enabled, created_at, updated_at)"
                        + " VALUES (:name, :kind, :subscriberId, :cursor, :batchSize, TRUE, :now, :now) RETURNING *")
                .param("name", name)
                .param("kind", kind)
                .param("subscriberId", subscriberId)
                .param("cursor", cursorPosition)
                .param("batchSize", batchSize)
                .param("now", now)
                .query(AuditSinkRepository::map)
                .single();
    }

    public List<AuditSink> list() {
        return jdbc.sql("SELECT * FROM audit_sinks ORDER BY id")
                .query(AuditSinkRepository::map)
                .list();
    }

    public List<AuditSink> listEnabled() {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE enabled ORDER BY id")
                .query(AuditSinkRepository::map)
                .list();
    }

    public Optional<AuditSink> findById(long id) {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE id = :id")
                .param("id", id)
                .query(AuditSinkRepository::map)
                .optional();
    }

    public Optional<AuditSink> findByName(String name) {
        return jdbc.sql("SELECT * FROM audit_sinks WHERE name = :name")
                .param("name", name)
                .query(AuditSinkRepository::map)
                .optional();
    }

    /**
     * Moves the sink's cursor. Used both to advance after a durable delivery and to rewind for a
     * replay (GW_0029) — the same single-column write in both directions.
     */
    public Optional<AuditSink> updateCursor(long id, long cursorPosition) {
        return jdbc.sql("UPDATE audit_sinks SET cursor_position = :cursor, updated_at = :now"
                        + " WHERE id = :id RETURNING *")
                .param("cursor", cursorPosition)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(AuditSinkRepository::map)
                .optional();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM audit_sinks WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    static AuditSink map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditSink(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getLong("subscriber_id"),
                rs.getLong("cursor_position"),
                rs.getInt("batch_size"),
                rs.getBoolean("enabled"),
                MarketplaceRepository.instant(rs, "created_at"),
                MarketplaceRepository.instant(rs, "updated_at"));
    }
}
