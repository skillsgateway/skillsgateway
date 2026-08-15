package io.github.jimisola.skillsgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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
                .query(WebhookSubscriberRepository::map)
                .single();
    }

    public List<WebhookSubscriber> list() {
        return jdbc.sql("SELECT * FROM webhook_subscribers ORDER BY id")
                .query(WebhookSubscriberRepository::map)
                .list();
    }

    public List<WebhookSubscriber> listEnabled() {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE enabled ORDER BY id")
                .query(WebhookSubscriberRepository::map)
                .list();
    }

    public Optional<WebhookSubscriber> findById(long id) {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE id = :id")
                .param("id", id)
                .query(WebhookSubscriberRepository::map)
                .optional();
    }

    public Optional<WebhookSubscriber> findByName(String name) {
        return jdbc.sql("SELECT * FROM webhook_subscribers WHERE name = :name")
                .param("name", name)
                .query(WebhookSubscriberRepository::map)
                .optional();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM webhook_subscribers WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    static WebhookSubscriber map(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookSubscriber(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("url"),
                rs.getString("secret"),
                rs.getString("events"),
                rs.getBoolean("enabled"),
                MarketplaceRepository.instant(rs, "created_at"));
    }
}
