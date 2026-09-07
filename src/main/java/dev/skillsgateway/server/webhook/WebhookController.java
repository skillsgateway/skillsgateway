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
     * The subscribable vocabulary <em>and</em> the shape of what arrives — a subscriber that reads
     * this knows what it may filter on and what it will have to parse.
     *
     * <p>The two example bodies are what put {@code EventPayload} and {@code ApprovalPendingPayload}
     * inside the {@code paths} surface the contract gate diffs, where a removed field is an error
     * rather than the warning it is on the request side of a {@code webhooks} entry. That placement
     * is the code half of GW_0182 — the other half is the workflow and its severity file, which no
     * annotation can carry. They are illustrative constants, never a real delivery: a delivery
     * arrives at the subscriber's own URL, signed, and nothing about it is readable here.
     */
    @Schema(description = "The subscribable lifecycle events and the shape of the deliveries that carry them")
    @Requirements({"GW_0182"})
    public record EventRegistry(
            @Schema(
                    description = "Every snapshot lifecycle event a subscriber may filter on",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> events,

            @Schema(
                    description = "An illustrative body of the kind every event other than"
                            + " snapshot.approval_pending delivers. Example values, not a real delivery.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            WebhookService.EventPayload examplePayload,

            @Schema(
                    description = "An illustrative body of the kind snapshot.approval_pending delivers: the"
                            + " same fields plus the vetting summary. Example values, not a real delivery.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            WebhookService.ApprovalPendingPayload exampleApprovalPendingPayload) {}

    private static final WebhookService.EventPayload EXAMPLE_PAYLOAD = new WebhookService.EventPayload(
            WebhookEvent.SNAPSHOT_APPROVED,
            "2026-01-01T00:00:00Z",
            "example-marketplace",
            1,
            "0000000000000000000000000000000000000000",
            "approved",
            "reviewer@example.com");

    private static final WebhookService.ApprovalPendingPayload EXAMPLE_APPROVAL_PENDING_PAYLOAD =
            new WebhookService.ApprovalPendingPayload(
                    WebhookEvent.SNAPSHOT_APPROVAL_PENDING,
                    "2026-01-01T00:00:00Z",
                    "example-marketplace",
                    1,
                    "0000000000000000000000000000000000000000",
                    "held",
                    "scheduler",
                    new WebhookService.VettingSummary(1, "BLOCKED", "BLOCKED", List.of("example-connector"), 1, 0));

    /**
     * The filter vocabulary, served so the portal can offer it instead of asking an operator to
     * spell it. {@link WebhookEvent#AUDIT_EXPORT} is absent by construction: it is not in
     * {@code ALL}, and subscribing to it is not how an export sink is provisioned.
     */
    @GetMapping("/events")
    @Requirements({"GW_0088", "GW_0181"})
    @Tag(name = "Webhooks")
    @Operation(
            summary = "List the subscribable lifecycle events and the shape they deliver",
            description = "Every snapshot lifecycle event a subscriber may filter on, together with an"
                    + " illustrative example of each delivery body — so a receiver can be written against"
                    + " what it will actually parse. The per-event deliveries are described in full under"
                    + " the document's top-level `webhooks` object. Read-only, records nothing. The audit"
                    + " export event is not subscribable and never appears here.")
    @ApiResponse(responseCode = "200", description = "The event registry")
    public EventRegistry events(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return new EventRegistry(WebhookEvent.ALL, EXAMPLE_PAYLOAD, EXAMPLE_APPROVAL_PENDING_PAYLOAD);
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
