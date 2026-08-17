package io.github.jimisola.skillsgateway.sync;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled polling sweep (GW_0057). Enabled by default and still safe on upgrade: it only
 * ever touches marketplaces an operator has explicitly moved to the {@code scheduled} sync mode,
 * so an estate of defaults sees no behavior change.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;
    private final SkillsGatewayProperties.Sync properties;

    public SyncScheduler(SyncService syncService, SkillsGatewayProperties properties) {
        this.syncService = syncService;
        this.properties = properties.sync();
    }

    @Requirements({"GW_0057"})
    @Scheduled(
            fixedDelayString = "${skills-gateway.sync.poll-interval:10m}",
            initialDelayString = "${skills-gateway.sync.poll-interval:10m}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        try {
            int ingested = syncService.sweep(properties.batchSize());
            if (ingested > 0) {
                log.info("scheduled sync pass ingested {} marketplaces", ingested);
            }
        } catch (RuntimeException e) {
            log.warn("scheduled sync pass failed", e);
        }
    }
}
