package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;

public record Snapshot(
        long id,
        long marketplaceId,
        String sha,
        String state,
        String violation,
        Instant createdAt,
        String decidedBy,
        Instant decidedAt) {

    public static final String HELD = "held";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
}
