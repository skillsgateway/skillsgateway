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
import java.util.function.Supplier;
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

    /**
     * What the vetting chain concluded about a snapshot awaiting approval (GW_0160): counts,
     * connector names and identifiers, and deliberately nothing else.
     *
     * <p>No finding message, rule id or location appears here. A webhook target is authorised by a
     * URL scheme allowlist, not by an identity, and a finding's location is a path inside
     * quarantined content while its message quotes what was found — so the event announces and
     * {@code GET /api/snapshots/{id}/vetting} discloses, to an authenticated caller. The
     * {@code snapshotId} and {@code runId} in the payload are what address that endpoint.
     *
     * <p>Every field is always present; {@code requiredMode = REQUIRED} says so on the wire, which
     * is what gives a contract diff something to fail on when one is removed (#121).
     */
    @Schema(description = "Content-free summary of the vetting chain run a snapshot is waiting on")
    public record VettingSummary(
            @Schema(
                    description = "Identifier of the chain run this event reports",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            long runId,

            @Schema(
                    description = "The effective outcome, which is what gates approval: the run with every waived"
                            + " finding removed. CLEAR means an approval will succeed; CLEAR_WITH_WAIVERS that it"
                            + " will, and only because someone accepted a risk; BLOCKED that it will not.",
                    allowableValues = {"CLEAR", "CLEAR_WITH_WAIVERS", "BLOCKED"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String outcome,

            @Schema(
                    description = "What the connectors themselves concluded, before any waiver was applied",
                    allowableValues = {"CLEAR", "BLOCKED"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String recordedOutcome,

            @Schema(
                    description =
                            "Names of the connectors that are the reason it blocks; empty when nothing" + " objects",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> blockingConnectors,

            @Schema(
                    description = "How many blocking findings no active waiver covers",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int uncoveredFindings,

            @Schema(
                    description = "How many findings an active waiver is currently suppressing",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int waivedFindings) {

        public VettingSummary {
            blockingConnectors = blockingConnectors == null ? List.of() : List.copyOf(blockingConnectors);
        }
    }

    /**
     * The {@code snapshot.approval_pending} body (GW_0159, GW_0160): the seven fields every
     * lifecycle event carries, in the same names and order as {@link EventPayload}, plus the
     * vetting summary. Keeping the shared half identical is what makes the event free to adopt for
     * a receiver already parsing another one — one unknown key, nothing else to change.
     */
    @Schema(description = "Payload of the approval-pending lifecycle event")
    public record ApprovalPendingPayload(
            @Schema(description = "Lifecycle event name", requiredMode = Schema.RequiredMode.REQUIRED)
            String event,

            @Schema(description = "Event time, ISO-8601", requiredMode = Schema.RequiredMode.REQUIRED)
            String occurredAt,

            @Schema(description = "Marketplace name", requiredMode = Schema.RequiredMode.REQUIRED)
            String marketplace,

            @Schema(description = "Snapshot id", requiredMode = Schema.RequiredMode.REQUIRED)
            long snapshotId,

            @Schema(
                    description = "Upstream commit SHA the snapshot is pinned to",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String sha,

            @Schema(
                    description = "Snapshot state; always held for this event",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String state,

            @Schema(description = "Acting identity", requiredMode = Schema.RequiredMode.REQUIRED)
            String actor,

            @Schema(
                    description = "What the chain concluded about the snapshot being waited on",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            VettingSummary vetting) {}

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
        return fanOut(
                event,
                () -> new EventPayload(event, Instant.now().toString(), marketplace, snapshotId, sha, state, actor));
    }

    /**
     * The payload-rich emit (GW_0159, GW_0160): {@code snapshot.approval_pending} with the vetting
     * summary a receiver triages on.
     *
     * <p>Typed to its payload rather than offered as a general {@code emit(String, Object)}. That is
     * a security choice, not a style one: an {@code Object} payload is an open door for a later
     * caller to hand the dispatcher something content-bearing, and keeping quarantined content off
     * this wire is the one rule the event must never break.
     */
    @Requirements({"GW_0159", "GW_0160"})
    public List<WebhookDelivery> emitApprovalPending(
            String marketplace, long snapshotId, String sha, String state, String actor, VettingSummary vetting) {
        return fanOut(
                WebhookEvent.SNAPSHOT_APPROVAL_PENDING,
                () -> new ApprovalPendingPayload(
                        WebhookEvent.SNAPSHOT_APPROVAL_PENDING,
                        Instant.now().toString(),
                        marketplace,
                        snapshotId,
                        sha,
                        state,
                        actor,
                        vetting));
    }

    /**
     * The one fan-out: queue a delivery per enabled subscriber whose filter includes the event, and
     * none for any other. The payload is built and serialized once, only when there is at least one
     * receiver, so every retry of a delivery sends identical bytes.
     */
    private List<WebhookDelivery> fanOut(String event, Supplier<Object> payloadFactory) {
        List<WebhookSubscriber> subscribers = subscriberRepository.listEnabled().stream()
                .filter(subscriber -> subscriber.subscribesTo(event))
                .toList();
        if (subscribers.isEmpty()) {
            return List.of();
        }
        String payload = serialize(event, payloadFactory.get());
        return subscribers.stream()
                .map(subscriber -> deliveryRepository.enqueue(subscriber.id(), event, payload))
                .toList();
    }

    private String serialize(String event, Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("webhook payload serialization failed for event {}", event, e);
            throw new IllegalStateException("webhook payload serialization failed", e);
        }
    }
}
