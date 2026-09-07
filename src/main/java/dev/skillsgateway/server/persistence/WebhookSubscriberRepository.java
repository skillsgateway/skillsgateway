package dev.skillsgateway.server.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookSubscriberRepository {

    private final JdbcClient jdbc;

    public WebhookSubscriberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WebhookSubscriber create(String name, String url, String secret, String events) {
        return jdbc.sql("INSERT INTO webhook_subscribers (name, url, secret, events, enabled, created_at)"
                        + " VALUES (:name, :url, :secret, :events, TRUE, :now) RETURNING *")
                .param("name", name)
                .param("url", url)
                .param("secret", secret)
                .param("events", events)
                .param("now", OffsetDateTime.now())
                .query(WebhookSubscriber.class)
                .single();
    }

    public List<WebhookSubscriber> list() {
        return jdbc.sql("SELECT * FROM webhook_subscribers ORDER BY id")
                .query(WebhookSubscriber.class)
                .list();
    }

    public List<WebhookSubscriber> listEnabled() {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE enabled ORDER BY id")
                .query(WebhookSubscriber.class)
                .list();
    }

    public Optional<WebhookSubscriber> findById(long id) {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE id = :id")
                .param("id", id)
                .query(WebhookSubscriber.class)
                .optional();
    }

    public Optional<WebhookSubscriber> findByName(String name) {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE name = :name")
                .param("name", name)
                .query(WebhookSubscriber.class)
                .optional();
    }

    /**
     * Converges a subscriber's target, secret and filter to a declared state (GW_0086). The caller
     * — the estate reconciler — has already diffed, so a call here is always a real change; the
     * secret value never appears anywhere but this parameter and the stored row.
     */
    public Optional<WebhookSubscriber> update(long id, String url, String secret, String events) {
        return jdbc.sql("UPDATE webhook_subscribers SET url = :url, secret = :secret, events = :events"
                        + " WHERE id = :id RETURNING *")
                .param("url", url)
                .param("secret", secret)
                .param("events", events)
                .param("id", id)
                .query(WebhookSubscriber.class)
                .optional();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM webhook_subscribers WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }
}
