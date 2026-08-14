package io.github.jimisola.skillsgateway.persistence;

import java.time.Instant;

public record AccessToken(
        long id, String principal, String name, String tokenHash, Instant createdAt, Instant revokedAt) {}
