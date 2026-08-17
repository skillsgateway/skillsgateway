package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The continuous re-vetting sweep on its schedule (GW_0049).
 *
 * <p>The pass is a no-op while {@code skills-gateway.vetting.revet.enabled} is false, and — much
 * more importantly — it retracts nothing at all while
 * {@code skills-gateway.vetting.revet.mode} is {@code warn}, which is the default. Enabling the
 * sweep starts producing evidence; taking content away is a second, separate decision an operator
 * has to make on purpose.
 */
@Component
public class RevetScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevetScheduler.class);

    private final RevetService revetService;
    private final SkillsGatewayProperties.Revet properties;

    public RevetScheduler(RevetService revetService, SkillsGatewayProperties properties) {
        this.revetService = revetService;
        this.properties = properties.vetting().revet();
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.vetting.revet.interval:6h}",
            initialDelayString = "${skills-gateway.vetting.revet.interval:6h}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        try {
            RevetService.PassResult result = revetService.sweep(RevetService.SWEEP_ACTOR);
            if (result.violations() > 0 || result.inconclusive() > 0) {
                log.warn(
                        "re-vetting pass: {} snapshot(s) re-vetted, {} violation(s), {} revoked,"
                                + " {} inconclusive (mode {})",
                        result.revetted(),
                        result.violations(),
                        result.revoked(),
                        result.inconclusive(),
                        properties.mode());
            } else if (result.revetted() > 0) {
                log.info("re-vetting pass re-vetted {} approved snapshot(s), all clear", result.revetted());
            }
        } catch (RuntimeException e) {
            log.warn("re-vetting sweep failed", e);
        }
    }
}
