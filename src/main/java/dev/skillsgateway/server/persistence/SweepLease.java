package dev.skillsgateway.server.persistence;

import java.time.OffsetDateTime;

/** One scheduled sweep's cross-replica lease (GW_FACADE_0030). */
public record SweepLease(String name, OffsetDateTime leasedUntil, String holder, OffsetDateTime updatedAt) {}
