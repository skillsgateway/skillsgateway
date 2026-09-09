package dev.skillsgateway.server.retention;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.scheduling.SweepLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two retention passes on their own schedules (GW_RETENTION_0001, GW_RETENTION_0004). Both are no-ops while
 * {@code skills-gateway.retention.enabled} is false, which is the default: the gateway never
 * deletes its own content because of an upgrade, only because an operator asked it to.
 *
 * <p>The compaction pass also sweeps abandoned publication staging references (GW_FACADE_0019), and is
 * behind the same switch for the same reason.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    /**
     * The two passes' cross-replica lease keys (GW_FACADE_0030). Two keys and not one: the passes
     * run on different intervals, and a six-hourly compaction must not keep the hourly evaluation
     * out for six hours.
     */
    public static final String EVALUATE_LEASE = "retention-evaluate";

    public static final String COMPACT_LEASE = "retention-compact";

    private final RetentionService retentionService;
    private final SkillsGatewayProperties.Retention properties;
    private final SweepLeases leases;

    public RetentionScheduler(
            RetentionService retentionService, SkillsGatewayProperties properties, SweepLeases leases) {
        this.retentionService = retentionService;
        this.properties = properties.retention();
        this.leases = leases;
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.retention.poll-interval:1h}",
            initialDelayString = "${skills-gateway.retention.poll-interval:1h}")
    public void evaluate() {
        if (!properties.enabled()) {
            return;
        }
        leases.runIfLeader(EVALUATE_LEASE, properties.pollInterval(), this::evaluateNow);
    }

    /** One evaluation pass. The enabled flag and the lease belong to the scheduled wrapper. */
    void evaluateNow() {
        try {
            RetentionService.PassResult result = retentionService.evaluate(RetentionService.POLICY_ACTOR);
            if (result.acted() > 0) {
                log.info("retention pass soft-deleted {} of {} selected snapshots", result.acted(), result.selected());
            }
        } catch (RuntimeException e) {
            log.warn("retention evaluation pass failed", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.retention.compaction-interval:6h}",
            initialDelayString = "${skills-gateway.retention.compaction-interval:6h}")
    public void compact() {
        if (!properties.enabled()) {
            return;
        }
        // Twice the interval: this pass runs git gc, which can legitimately outlast one period, and
        // a lease that lapses under a running compaction is a second replica starting another.
        leases.runIfLeader(COMPACT_LEASE, properties.compactionInterval().multipliedBy(2), this::compactNow);
    }

    /** One compaction pass. The enabled flag and the lease belong to the scheduled wrapper. */
    void compactNow() {
        try {
            RetentionService.PassResult result = retentionService.compact(RetentionService.POLICY_ACTOR);
            if (result.acted() > 0) {
                log.info("retention compaction purged {} snapshots", result.acted());
            }
        } catch (RuntimeException e) {
            log.warn("retention compaction pass failed", e);
        }
    }
}
