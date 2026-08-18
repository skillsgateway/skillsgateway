package dev.skillsgateway.server.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Subscriber lifecycle and event fan-out. Emission is enqueue-only: an administrative action
 * never blocks on, or fails because of, a receiver — the dispatcher delivers out of band.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_PREFIX = "whsec_";

    public static final Pattern SUBSCRIBER_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    /** Webhook administration is not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final WebhookSubscriberRepository subscriberRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final SkillsGatewayProperties properties;
    private final AdminAuditLogger auditLogger;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(
            WebhookSubscriberRepository subscriberRepository,
            WebhookDeliveryRepository deliveryRepository,
            SkillsGatewayProperties properties,
            AdminAuditLogger auditLogger) {
        this.subscriberRepository = subscriberRepository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        this.auditLogger = auditLogger;
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
        return createSubscriber(name, url, events, null);
    }

    /**
     * As {@link #createSubscriber(String, String, String)}, with an operator-supplied secret when
     * the caller is the estate reconciler (GW_0086); null generates one, as the API always does.
     */
    @Requirements({"GW_0024"})
    public CreatedSubscriber createSubscriber(String name, String url, String events, String suppliedSecret) {
        String secret = suppliedSecret == null ? generateSecret() : suppliedSecret;
        WebhookSubscriber stored = subscriberRepository.create(name, url, secret, events);
        return new CreatedSubscriber(
                stored.id(), stored.name(), stored.url(), stored.events(), secret, stored.createdAt());
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The one subscriber registration path (GW_0024, GW_0086): validates the name, the target URL
     * scheme against the allowlist and the event filter, refuses a duplicate name, creates the
     * subscriber, and appends the ledger entry with the acting identity — whether the caller is the
     * webhooks API (null secret, generated show-once) or the estate reconciler (operator-supplied
     * secret). Statuses match the API contract; a non-HTTP caller reports the reason instead.
     */
    @Requirements({"GW_0024"})
    public CreatedSubscriber register(String name, String url, String events, String suppliedSecret, String actor) {
        if (name == null || !SUBSCRIBER_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "name must match " + SUBSCRIBER_NAME.pattern());
        }
        requireAllowlistedScheme(url);
        String normalizedEvents = normalizeEvents(events);
        if (findSubscriber(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "subscriber '%s' already exists".formatted(name));
        }
        CreatedSubscriber created = createSubscriber(name, url, normalizedEvents, suppliedSecret);
        auditLogger.record(actor, NO_MARKETPLACE, "webhook-subscriber-created", null);
        return created;
    }

    /**
     * The target-URL gate for callers that converge an existing subscriber in place (the estate
     * reconciler): an updated target faces the same allowlist as a created one (GW_0086).
     */
    public void validateTarget(String url) {
        requireAllowlistedScheme(url);
    }

    /** Fails closed, exactly like marketplace registration (GW_0016). */
    private void requireAllowlistedScheme(String url) {
        String scheme = null;
        if (url != null) {
            try {
                scheme = new URI(url).getScheme();
            } catch (URISyntaxException e) {
                scheme = null;
            }
        }
        if (scheme == null || !properties.allowedUrlSchemes().contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "url scheme must be one of %s".formatted(properties.allowedUrlSchemes()));
        }
    }

    /** Blank filter means every event; unknown event names are rejected instead of silently ignored. */
    public static String normalizeEvents(String events) {
        if (events == null || events.isBlank()) {
            return WebhookSubscriber.ALL_EVENTS;
        }
        List<String> requested = List.of(events.split(",")).stream()
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .toList();
        if (requested.isEmpty()) {
            return WebhookSubscriber.ALL_EVENTS;
        }
        for (String event : requested) {
            if (!WebhookSubscriber.ALL_EVENTS.equals(event) && !WebhookEvent.ALL.contains(event)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unknown event '%s'; known events: %s".formatted(event, WebhookEvent.ALL));
            }
        }
        return String.join(",", requested);
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
