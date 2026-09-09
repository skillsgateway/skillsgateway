package dev.skillsgateway.server.scheduling;

import dev.skillsgateway.server.persistence.SweepLeaseRepository;
import io.github.reqstool.annotations.Requirements;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes each scheduled sweep run on one replica per interval (GW_FACADE_0030).
 *
 * <p>The sweeps are singletons by construction — every one of them enumerates rows the whole
 * estate shares — so N replicas meant N sync passes, N re-vetting passes and N exporters
 * advancing the same cursor. The chart's answer was to refuse to render a scaled-out deployment
 * unless the operator turned them off, which made the object-store backend scalable only with the
 * estate's own maintenance disabled.
 *
 * <p><b>The acquire never blocks.</b> Boot's default {@code TaskScheduler} has a pool size of one,
 * so all eight sweeps share a thread; an acquire that waited on the six-hourly re-vet lease would
 * stall the five-second webhook poll behind it. A refused sweep is a sweep that runs on the other
 * replica, which is the point — so losing is DEBUG, not a warning.
 *
 * <p><b>The lease is never released early.</b> It lapses on its own after the sweep's own
 * interval, which gives the estate at most one pass per interval rather than one pass per replica
 * per interval, and makes a replica that dies mid-pass cost exactly what a replica that finished
 * one costs: the next tick. There is no unlock to leak and no cleanup path to get wrong.
 */
@Component
public class SweepLeases {

    private static final Logger log = LoggerFactory.getLogger(SweepLeases.class);

    private final SweepLeaseRepository repository;
    private final String holder;

    public SweepLeases(SweepLeaseRepository repository) {
        this.repository = repository;
        this.holder = localHolder();
    }

    /** The identity written onto every lease this replica takes. */
    public String holder() {
        return holder;
    }

    /**
     * Runs {@code body} if this replica takes the named lease, and reports whether it did.
     *
     * @param name the sweep's own key constant — never a class name, which a rename would change
     * @param lease how long the sweep's turn lasts; the sweep's own interval, so a lease cannot
     *     outlive the gap it protects
     */
    @Requirements({"GW_FACADE_0030"})
    public boolean runIfLeader(String name, Duration lease, Runnable body) {
        Instant now = Instant.now();
        if (!repository.claim(name, holder, now.plus(lease), now)) {
            log.debug("sweep {} skipped: another replica holds the lease", name);
            return false;
        }
        body.run();
        return true;
    }

    /**
     * The pod hostname, which under Kubernetes is the pod name. A random suffix rather than a
     * constant when the host cannot be resolved: two replicas that both fall back must not claim
     * to be the same holder, or the column stops answering the one question it exists for.
     */
    private static String localHolder() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            String fallback = "unknown-" + UUID.randomUUID();
            log.warn("could not resolve this host's name; sweep leases will be held by {}", fallback);
            return fallback;
        }
    }
}
