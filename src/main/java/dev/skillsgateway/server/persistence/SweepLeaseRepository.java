package dev.skillsgateway.server.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SweepLeaseRepository {

    private final JdbcClient jdbc;

    public SweepLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the named lease if it is free or lapsed, in one statement. Deliberately the same shape
     * as {@link WebhookDeliveryRepository#claim(long, Instant)}: the conditional write is the
     * atomicity, so there is nothing to hold across a connection and nothing to release.
     *
     * <p>Returns empty when another holder still owns it. A lapsed lease is taken without ceremony
     * — that is the whole crash-recovery story, and why the holder never has to be cleaned up.
     */
    public boolean claim(String name, String holder, Instant leaseUntil, Instant now) {
        return jdbc.sql("INSERT INTO sweep_leases (name, leased_until, holder, updated_at)"
                                + " VALUES (:name, :lease, :holder, :now)"
                                + " ON CONFLICT (name) DO UPDATE SET leased_until = :lease, holder = :holder,"
                                + " updated_at = :now WHERE sweep_leases.leased_until <= :now")
                        .param("name", name)
                        .param("lease", leaseUntil.atOffset(ZoneOffset.UTC))
                        .param("holder", holder)
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .update()
                > 0;
    }

    /** Who holds the lease and until when; for diagnostics, never for a decision. */
    public Optional<SweepLease> find(String name) {
        return jdbc.sql("SELECT * FROM sweep_leases WHERE name = :name")
                .param("name", name)
                .query(SweepLease.class)
                .optional();
    }
}
