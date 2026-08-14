package io.github.jimisola.skillsgateway.webhook;

import io.github.jimisola.skillsgateway.admin.AdminAuditLogger;
import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.WebhookDelivery;
import io.github.jimisola.skillsgateway.persistence.WebhookSubscriber;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Pattern SUBSCRIBER_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    /** Webhook administration is not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private static final int DEFAULT_DELIVERY_LIMIT = 100;
    private static final int MAX_DELIVERY_LIMIT = 500;

    private final WebhookService webhookService;
    private final SkillsGatewayProperties properties;
    private final AdminAuditLogger auditLogger;

    public WebhookController(
            WebhookService webhookService, SkillsGatewayProperties properties, AdminAuditLogger auditLogger) {
        this.webhookService = webhookService;
        this.properties = properties;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "Webhook subscriber registration request")
    public record CreateSubscriberRequest(
            @Schema(
                    description = "Gateway-local subscriber name",
                    example = "ci-bot",
                    pattern = "^[a-z0-9][a-z0-9_-]*$")
            String name,

            @Schema(
                    description = "Target URL; scheme must be on the configured allowlist" + " (default http/https)",
                    example = "https://ci.example.com/hooks/skills-gateway")
            String url,

            @Schema(
                    description = "Comma-delimited event filter, or * for every event",
                    example = "snapshot.approved,snapshot.rejected")
            String events) {}

    /** Never exposes the signing secret; it is returned only by the creation response. */
    @Schema(description = "A webhook subscriber without its signing secret")
    public record SubscriberView(
            @Schema(description = "Subscriber id") long id,
            @Schema(description = "Subscriber name") String name,
            @Schema(description = "Target URL") String url,

            @Schema(description = "Comma-delimited event filter, or *")
            String events,

            @Schema(description = "Whether deliveries are queued for this subscriber")
            boolean enabled,

            @Schema(description = "Creation time") Instant createdAt) {}

    @PostMapping
    @Tag(name = "Webhooks")
    @Operation(
            summary = "Register a webhook subscriber",
            description = "Registers a receiver for snapshot lifecycle events. The signing secret is returned"
                    + " exactly once, in this response, and is never readable afterwards.")
    @ApiResponse(responseCode = "201", description = "Subscriber registered; response carries the show-once secret")
    @ApiResponse(responseCode = "400", description = "Disallowed URL scheme, or an unknown event name")
    @ApiResponse(responseCode = "409", description = "A subscriber with that name already exists")
    @ApiResponse(responseCode = "422", description = "Invalid subscriber name")
    public ResponseEntity<WebhookService.CreatedSubscriber> create(
            @RequestBody CreateSubscriberRequest request, Authentication authentication) {
        if (request.name() == null || !SUBSCRIBER_NAME.matcher(request.name()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "name must match " + SUBSCRIBER_NAME.pattern());
        }
        requireAllowlistedScheme(request.url());
        String events = normalizeEvents(request.events());
        if (webhookService.findSubscriber(request.name()).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "subscriber '%s' already exists".formatted(request.name()));
        }
        WebhookService.CreatedSubscriber created =
                webhookService.createSubscriber(request.name(), request.url(), events);
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "webhook-subscriber-created", null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Tag(name = "Webhooks")
    @Operation(
            summary = "List webhook subscribers",
            description = "Every registered receiver with its event filter. Signing secrets are never returned.")
    public List<SubscriberView> list() {
        return webhookService.listSubscribers().stream()
                .map(WebhookController::view)
                .toList();
    }

    @DeleteMapping("/{id}")
    @Tag(name = "Webhooks")
    @Operation(
            summary = "Delete a webhook subscriber",
            description = "Removes the subscriber and its delivery history; no further events are queued for it.")
    @ApiResponse(responseCode = "204", description = "Subscriber deleted")
    @ApiResponse(responseCode = "404", description = "Subscriber not found")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        if (!webhookService.deleteSubscriber(id)) {
            return ResponseEntity.notFound().build();
        }
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "webhook-subscriber-deleted", null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/deliveries")
    @Requirements({"GW_0026"})
    @Tag(name = "Webhooks")
    @Operation(
            summary = "List recent delivery attempts",
            description = "Most recent deliveries first, with state, attempt count, and the last response status"
                    + " or error — the operator's view of a failing integration.")
    public List<WebhookDelivery> deliveries(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_DELIVERY_LIMIT) int limit) {
        return webhookService.listDeliveries(Math.clamp(limit, 1, MAX_DELIVERY_LIMIT));
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
    private static String normalizeEvents(String events) {
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

    private static SubscriberView view(WebhookSubscriber subscriber) {
        return new SubscriberView(
                subscriber.id(),
                subscriber.name(),
                subscriber.url(),
                subscriber.events(),
                subscriber.enabled(),
                subscriber.createdAt());
    }
}
