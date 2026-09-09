package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.scheduling.SweepLeases;
import io.github.reqstool.annotations.Requirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The bound on how long the mirror may disagree with what the facade serves (GW_FACADE_0025).
 *
 * <p>Everything else about the mirror is stated in the negative: a push that fails changes nothing,
 * a revocation still takes effect, an outage is contained. The cost of that safety is <b>duration</b>
 * — as first shipped, a revocation whose push exhausted its retries left the revoked reference on
 * the mirror until the next approval or revocation of that marketplace, which on a marketplace
 * nobody touches again is never. This class is what makes that finite.
 *
 * <p>It is the same reconciliation, on a timer. That is the whole design, and it is small because
 * the reconciliation was already defined as "make the mirror equal what is served now" rather than
 * as a delta: running it again is always safe, always idempotent, and always converges. The three
 * failure modes it closes are a push that gave up, a queue lost to a restart (the queue is in
 * memory, deliberately), and a change somebody made on the forge by hand — the last two being
 * exactly the cases a durable retry queue would not have covered.
 *
 * <p><b>It has no authority over anything.</b> It queues work on the mirror's own thread and
 * returns; it cannot delay, block or reverse an approval, a revocation, or what the facade serves,
 * and it contacts nothing at all while the mirror is disabled — which is the shipped default. The
 * {@code catch} is the same latch {@code MirrorPublicationListener} carries: a {@code @Scheduled}
 * method that throws is logged and the schedule continues, but that is a convention of a framework
 * we do not own, and a sweep that stopped running would take the bound with it silently.
 */
@Component
public class MirrorReconciliationSweep {

    private static final Logger log = LoggerFactory.getLogger(MirrorReconciliationSweep.class);

    /** The reason recorded on the ledger and in the logs for a reconciliation nothing asked for. */
    public static final String REASON = "drift-sweep";

    /** This sweep's cross-replica lease key (GW_FACADE_0030). */
    public static final String LEASE = "mirror-drift";

    private final ForgeMirrorService mirror;
    private final SkillsGatewayProperties.Mirror properties;
    private final SweepLeases leases;

    public MirrorReconciliationSweep(
            ForgeMirrorService mirror, SkillsGatewayProperties properties, SweepLeases leases) {
        this.mirror = mirror;
        this.properties = properties.mirror();
        this.leases = leases;
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.mirror.sweep-interval:15m}",
            initialDelayString = "${skills-gateway.mirror.sweep-initial-delay:1m}")
    @Requirements({"GW_FACADE_0025"})
    public void sweep() {
        if (!mirror.sweepEnabled()) {
            return;
        }
        leases.runIfLeader(LEASE, properties.sweepInterval(), this::sweepNow);
    }

    /**
     * Queues one reconciliation. The scheduled method above keeps the name {@code sweep} on
     * purpose: it is the name the task registry reports, and that string is asserted on.
     */
    public void sweepNow() {
        try {
            mirror.checkForDriftLater(REASON);
        } catch (RuntimeException e) {
            log.warn("forge mirror drift sweep could not be queued", e);
        }
    }
}
