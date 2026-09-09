package dev.skillsgateway.server.audit;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.scheduling.SweepLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs an export pass for every enabled sink. Enqueueing is all this does — the webhook dispatcher
 * added with the lifecycle events owns signing, sending, backoff, and the delivery record.
 */
@Component
public class AuditExportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditExportScheduler.class);

    /** This poller's cross-replica lease key (GW_FACADE_0030). */
    public static final String LEASE = "audit-export";

    private final AuditExportService exportService;
    private final SkillsGatewayProperties.AuditExport properties;
    private final SweepLeases leases;

    public AuditExportScheduler(
            AuditExportService exportService, SkillsGatewayProperties properties, SweepLeases leases) {
        this.exportService = exportService;
        this.properties = properties.auditExport();
        this.leases = leases;
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.audit-export.poll-interval:30s}",
            initialDelayString = "${skills-gateway.audit-export.poll-interval:30s}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        leases.runIfLeader(LEASE, properties.pollInterval(), this::pollNow);
    }

    /** One export pass. The enabled flag and the lease are the scheduled wrapper's business. */
    void pollNow() {
        try {
            exportService.exportPass();
        } catch (RuntimeException e) {
            log.warn("audit export pass failed", e);
        }
    }
}
