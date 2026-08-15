package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A registered webhook receiver. The {@code secret} is the HMAC signing key and is never
 * exposed by any read endpoint after creation (GW_0024).
 */
public record WebhookSubscriber(
        long id, String name, String url, String secret, String events, boolean enabled, Instant createdAt) {

    /** Event filter value subscribing to every lifecycle event. */
    public static final String ALL_EVENTS = "*";

    public List<String> eventFilter() {
        if (events == null || events.isBlank()) {
            return List.of();
        }
        return Arrays.stream(events.split(","))
                .map(String::trim)
                .filter(event -> !event.isEmpty())
                .toList();
    }

    /** True when this subscriber asked for {@code event} (GW_0023). */
    public boolean subscribesTo(String event) {
        List<String> filter = eventFilter();
        return filter.contains(ALL_EVENTS) || filter.contains(event);
    }
}
