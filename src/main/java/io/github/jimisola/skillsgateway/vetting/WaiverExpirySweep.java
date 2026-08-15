package io.github.jimisola.skillsgateway.vetting;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes a {@code waiver-expired} ledger entry the first time a waiver is seen past its expiry
 * (GW_0048).
 *
 * <p>This pass has no authority over the gate. A lapsed waiver stops suppressing its finding the
 * moment {@link WaiverService#evaluate(long)} next runs, because expiry is a comparison against
 * {@code now} and not a state anything transitions through (GW_0046). So the sweep can be delayed,
 * disabled, or never run at all without opening a hole — the only thing that changes is whether
 * the lapse is <em>announced</em> in the ledger rather than merely observable in it.
 *
 * <p>That is exactly why it is safe for it to be this simple: idempotent (the
 * {@code expired_recorded_at} stamp), bounded (one batch per pass), and free of any influence over
 * whether content is served.
 */
@Component
public class WaiverExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(WaiverExpirySweep.class);

    private final WaiverService waiverService;
    private final SkillsGatewayProperties.Vetting properties;

    public WaiverExpirySweep(WaiverService waiverService, SkillsGatewayProperties properties) {
        this.waiverService = waiverService;
        this.properties = properties.vetting();
    }

    @Scheduled(
            fixedDelayString = "${skills-gateway.vetting.waiver-sweep-interval:1h}",
            initialDelayString = "${skills-gateway.vetting.waiver-sweep-interval:1h}")
    public void sweep() {
        try {
            int recorded = waiverService.sweepExpired(properties.waiverSweepBatchSize());
            if (recorded > 0) {
                log.info("recorded {} newly expired vetting waiver(s) in the ledger", recorded);
            }
        } catch (RuntimeException e) {
            log.warn("vetting waiver expiry sweep failed", e);
        }
    }
}
