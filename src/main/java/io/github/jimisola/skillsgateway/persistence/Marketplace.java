package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;

public record Marketplace(
        long id,
        String name,
        String url,
        Instant createdAt,
        String forge,
        String forgeProject,
        String description,
        Instant upstreamUpdatedAt) {}
