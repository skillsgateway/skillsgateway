package dev.skillsgateway.server.persistence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "One webhook delivery: an event queued for a subscriber, with its attempt history")
public record WebhookDelivery(
        @Schema(description = "Delivery id; sent as the de-duplication header")
        long id,

        @Schema(description = "Owning subscriber id") long subscriberId,
        @Schema(description = "Lifecycle event name") String event,

        @Schema(description = "Serialized JSON body; the exact bytes that are signed and sent")
        String payload,

        @Schema(
                description = "pending, delivered, or failed",
                allowableValues = {"pending", "delivered", "failed"})
        String state,

        @Schema(description = "Attempts made so far") int attempts,

        @Schema(description = "When the next attempt becomes due")
        Instant nextAttemptAt,

        @Schema(description = "HTTP status of the last attempt, or null")
        Integer lastStatus,

        @Schema(description = "Error of the last attempt, or null")
        String lastError,

        @Schema(description = "Enqueue time") Instant createdAt,
        @Schema(description = "Last state change") Instant updatedAt) {

    public static final String PENDING = "pending";
    public static final String DELIVERED = "delivered";
    public static final String FAILED = "failed";
}
