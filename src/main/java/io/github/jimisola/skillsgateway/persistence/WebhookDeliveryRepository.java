package io.github.jimisola.skillsgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDeliveryRepository {

    private final JdbcClient jdbc;

    public WebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WebhookDelivery enqueue(long subscriberId, String event, String payload) {
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.sql("INSERT INTO webhook_deliveries"
                        + " (subscriber_id, event, payload, state, attempts, next_attempt_at, created_at, updated_at)"
                        + " VALUES (:subscriberId, :event, :payload, 'pending', 0, :now, :now, :now) RETURNING *")
                .param("subscriberId", subscriberId)
                .param("event", event)
                .param("payload", payload)
                .param("now", now)
                .query(WebhookDeliveryRepository::map)
                .single();
    }

    /** Ids of deliveries whose next attempt is due; claiming them is a separate atomic step. */
    public List<Long> dueIds(int limit) {
        return jdbc.sql("SELECT id FROM webhook_deliveries WHERE state = 'pending' AND next_attempt_at <= :now"
                        + " ORDER BY next_attempt_at LIMIT :limit")
                .param("now", OffsetDateTime.now())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /**
     * Atomically takes ownership of a due delivery by pushing its next attempt out by the lease:
     * a concurrent dispatcher's conditional update then matches no row and it moves on.
     */
    public Optional<WebhookDelivery> claim(long id, Instant leaseUntil) {
        return jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = :lease, updated_at = :now"
                        + " WHERE id = :id AND state = 'pending' AND next_attempt_at <= :now RETURNING *")
                .param("lease", leaseUntil.atOffset(ZoneOffset.UTC))
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .query(WebhookDeliveryRepository::map)
                .optional();
    }

    public void markDelivered(long id, int attempts, int status) {
        jdbc.sql("UPDATE webhook_deliveries SET state = 'delivered', attempts = :attempts,"
                        + " last_status = :status, last_error = NULL, updated_at = :now WHERE id = :id")
                .param("attempts", attempts)
                .param("status", status)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    public void markRetry(long id, int attempts, Instant nextAttemptAt, Integer status, String error) {
        jdbc.sql("UPDATE webhook_deliveries SET state = 'pending', attempts = :attempts,"
                        + " next_attempt_at = :next, last_status = :status, last_error = :error,"
                        + " updated_at = :now WHERE id = :id")
                .param("attempts", attempts)
                .param("next", nextAttemptAt.atOffset(ZoneOffset.UTC))
                .param("status", status)
                .param("error", error)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    public void markFailed(long id, int attempts, Integer status, String error) {
        jdbc.sql("UPDATE webhook_deliveries SET state = 'failed', attempts = :attempts,"
                        + " last_status = :status, last_error = :error, updated_at = :now WHERE id = :id")
                .param("attempts", attempts)
                .param("status", status)
                .param("error", error)
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
    }

    public Optional<WebhookDelivery> findById(long id) {
        return jdbc.sql("SELECT * FROM webhook_deliveries WHERE id = :id")
                .param("id", id)
                .query(WebhookDeliveryRepository::map)
                .optional();
    }

    public List<WebhookDelivery> listRecent(int limit) {
        return jdbc.sql("SELECT * FROM webhook_deliveries ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(WebhookDeliveryRepository::map)
                .list();
    }

    public List<WebhookDelivery> listBySubscriber(long subscriberId) {
        return jdbc.sql("SELECT * FROM webhook_deliveries WHERE subscriber_id = :subscriberId ORDER BY id")
                .param("subscriberId", subscriberId)
                .query(WebhookDeliveryRepository::map)
                .list();
    }

    static WebhookDelivery map(ResultSet rs, int rowNum) throws SQLException {
        int status = rs.getInt("last_status");
        // wasNull() reflects the column just read, so it must be evaluated here.
        Integer lastStatus = rs.wasNull() ? null : status;
        return new WebhookDelivery(
                rs.getLong("id"),
                rs.getLong("subscriber_id"),
                rs.getString("event"),
                rs.getString("payload"),
                rs.getString("state"),
                rs.getInt("attempts"),
                MarketplaceRepository.instant(rs, "next_attempt_at"),
                lastStatus,
                rs.getString("last_error"),
                MarketplaceRepository.instant(rs, "created_at"),
                MarketplaceRepository.instant(rs, "updated_at"));
    }
}
