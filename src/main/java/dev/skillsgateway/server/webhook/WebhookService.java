package dev.skillsgateway.server.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Subscriber lifecycle and event fan-out. Emission is enqueue-only: an administrative action
 * never blocks on, or fails because of, a receiver — the dispatcher delivers out of band.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_PREFIX = "whsec_";

    private final WebhookSubscriberRepository subscriberRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(
            WebhookSubscriberRepository subscriberRepository, WebhookDeliveryRepository deliveryRepository) {
        this.subscriberRepository = subscriberRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @Schema(description = "A freshly created subscriber; the only time the signing secret is ever returned")
    public record CreatedSubscriber(
            @Schema(description = "Subscriber id") long id,
            @Schema(description = "Subscriber name") String name,
            @Schema(description = "Target URL") String url,

            @Schema(description = "Comma-delimited event filter, or * for all")
            String events,

            @Schema(description = "Signing secret - shown exactly once")
            String secret,

            @Schema(description = "Creation time") Instant createdAt) {}

    /** The event body: what happened, to which snapshot of which marketplace, and who decided. */
    @Schema(description = "Webhook event payload")
    public record EventPayload(
            @Schema(description = "Lifecycle event name") String event,
            @Schema(description = "Event time, ISO-8601") String occurredAt,
            @Schema(description = "Marketplace name") String marketplace,
            @Schema(description = "Snapshot id") long snapshotId,

            @Schema(description = "Upstream commit SHA the snapshot is pinned to")
            String sha,

            @Schema(description = "Snapshot state after the event")
            String state,

            @Schema(description = "Acting identity") String actor) {}

    /** The secret is stored recoverably because signing needs it, and is never read back over the API. */
    @Requirements({"GW_0024"})
    public CreatedSubscriber createSubscriber(String name, String url, String events) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        WebhookSubscriber stored = subscriberRepository.create(name, url, secret, events);
        return new CreatedSubscriber(
                stored.id(), stored.name(), stored.url(), stored.events(), secret, stored.createdAt());
    }

    public List<WebhookSubscriber> listSubscribers() {
        return subscriberRepository.list();
    }

    public Optional<WebhookSubscriber> findSubscriber(String name) {
        return subscriberRepository.findByName(name);
    }

    public boolean deleteSubscriber(long id) {
        return subscriberRepository.delete(id);
    }

    public List<WebhookDelivery> listDeliveries(int limit) {
        return deliveryRepository.listRecent(limit);
    }

    /**
     * Queues one delivery per enabled subscriber whose filter includes the event, and none for any
     * other subscriber. The payload is serialized once here so every retry sends identical bytes.
     */
    @Requirements({"GW_0023"})
    public List<WebhookDelivery> emit(
            String event, String marketplace, long snapshotId, String sha, String state, String actor) {
        List<WebhookSubscriber> subscribers = subscriberRepository.listEnabled().stream()
                .filter(subscriber -> subscriber.subscribesTo(event))
                .toList();
        if (subscribers.isEmpty()) {
            return List.of();
        }
        String payload = serialize(
                new EventPayload(event, Instant.now().toString(), marketplace, snapshotId, sha, state, actor));
        return subscribers.stream()
                .map(subscriber -> deliveryRepository.enqueue(subscriber.id(), event, payload))
                .toList();
    }

    private String serialize(EventPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("webhook payload serialization failed for event {}", payload.event(), e);
            throw new IllegalStateException("webhook payload serialization failed", e);
        }
    }
}
