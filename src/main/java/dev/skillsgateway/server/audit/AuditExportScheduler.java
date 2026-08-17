package dev.skillsgateway.server.audit;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
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

    private final AuditExportService exportService;
    private final SkillsGatewayProperties.AuditExport properties;

    public AuditExportScheduler(AuditExportService exportService, SkillsGatewayProperties properties) {
        this.exportService = exportService;
        this.properties = properties.auditExport();
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.audit-export.poll-interval:30s}",
            initialDelayString = "${skills-gateway.audit-export.poll-interval:30s}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            exportService.exportPass();
        } catch (RuntimeException e) {
            log.warn("audit export pass failed", e);
        }
    }
}
