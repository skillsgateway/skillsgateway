package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;

/**
 * A registered consumer of the append-only audit ledger. The sink owns nothing but a cursor:
 * {@code cursorPosition} is the id of the last ledger entry handed to it, and no ledger entry is
 * ever copied into a per-sink queue (GW_0028).
 *
 * <p>{@code subscriberId} points at an ordinary {@link WebhookSubscriber}, so batches are signed,
 * retried, and recorded by the lifecycle webhook machinery rather than a second delivery engine.
 */
public record AuditSink(
        long id,
        String name,
        String kind,
        long subscriberId,
        long cursorPosition,
        int batchSize,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    /** The only sink kind accepted in v1. */
    public static final String WEBHOOK = "webhook";
}
