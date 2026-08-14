package io.github.jimisola.skillsgateway.retention;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two retention passes on their own schedules (GW_0031, GW_0034). Both are no-ops while
 * {@code skills-gateway.retention.enabled} is false, which is the default: the gateway never
 * deletes its own content because of an upgrade, only because an operator asked it to.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retentionService;
    private final SkillsGatewayProperties.Retention properties;

    public RetentionScheduler(RetentionService retentionService, SkillsGatewayProperties properties) {
        this.retentionService = retentionService;
        this.properties = properties.retention();
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.retention.poll-interval:1h}",
            initialDelayString = "${skills-gateway.retention.poll-interval:1h}")
    public void evaluate() {
        if (!properties.enabled()) {
            return;
        }
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
