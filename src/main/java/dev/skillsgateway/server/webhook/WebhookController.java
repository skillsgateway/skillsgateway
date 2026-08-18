package dev.skillsgateway.server.webhook;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
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

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    /** Webhook administration is not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private static final int DEFAULT_DELIVERY_LIMIT = 100;
    private static final int MAX_DELIVERY_LIMIT = 500;

    private final WebhookService webhookService;
    private final AdminAuditLogger auditLogger;
    private final RoleService roleService;

    public WebhookController(WebhookService webhookService, AdminAuditLogger auditLogger, RoleService roleService) {
        this.webhookService = webhookService;
        this.auditLogger = auditLogger;
        this.roleService = roleService;
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
        roleService.requireAdmin(authentication);
        WebhookService.CreatedSubscriber created = webhookService.register(
                request.name(), request.url(), request.events(), null, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Tag(name = "Webhooks")
    @Operation(
            summary = "List webhook subscribers",
            description = "Every registered receiver with its event filter. Signing secrets are never returned.")
    public List<SubscriberView> list(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return webhookService.listSubscribers().stream()
                .map(WebhookController::view)
                .toList();
    }

    /**
     * The filter vocabulary, served so the portal can offer it instead of asking an operator to
     * spell it. {@link WebhookEvent#AUDIT_EXPORT} is absent by construction: it is not in
     * {@code ALL}, and subscribing to it is not how an export sink is provisioned.
     */
    @GetMapping("/events")
    @Requirements({"GW_0088"})
    @Tag(name = "Webhooks")
    @Operation(
            summary = "List the subscribable lifecycle events",
            description = "Every snapshot lifecycle event a subscriber may filter on. Read-only, records"
                    + " nothing. The audit export event is not subscribable and never appears here.")
    @ApiResponse(responseCode = "200", description = "The event registry")
    public List<String> events(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return WebhookEvent.ALL;
    }

    @DeleteMapping("/{id}")
    @Tag(name = "Webhooks")
    @Operation(
            summary = "Delete a webhook subscriber",
            description = "Removes the subscriber and its delivery history; no further events are queued for it.")
    @ApiResponse(responseCode = "204", description = "Subscriber deleted")
    @ApiResponse(responseCode = "404", description = "Subscriber not found")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
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
            @RequestParam(required = false, defaultValue = "" + DEFAULT_DELIVERY_LIMIT) int limit,
            Authentication authentication) {
        roleService.requireAuditor(authentication);
        return webhookService.listDeliveries(Math.clamp(limit, 1, MAX_DELIVERY_LIMIT));
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
