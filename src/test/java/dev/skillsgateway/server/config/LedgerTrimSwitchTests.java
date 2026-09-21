package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The switch that arms the one pass in the gateway that deletes audit evidence.
 *
 * <p>No Spring context: this is a property record's own reading of its own value, and the whole
 * point is the direction it fails in. The mis-typed value must be the one that deletes nothing —
 * the same fail-safe reading {@code staging-ref-max-age} and {@code held-max-age} already get, for
 * the same reason, which is why it is not restated on each of them.
 */
class LedgerTrimSwitchTests {

    private static SkillsGatewayProperties.Retention with(Duration ledgerMaxAge) {
        return new SkillsGatewayProperties.Retention(true, null, null, null, null, ledgerMaxAge, null, Map.of());
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void the_trim_is_off_unless_an_age_is_configured() {
        assertThat(with(null).ledgerTrimEnabled())
                .as("unset is off: a deployment that never asked does not delete its ledger")
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void zero_and_negative_switch_it_off_rather_than_making_everything_eligible() {
        // The reading that matters: zero must not mean "older than now", which is every entry.
        assertThat(with(Duration.ZERO).ledgerTrimEnabled()).isFalse();
        assertThat(with(Duration.ofDays(-1)).ledgerTrimEnabled()).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void a_positive_age_arms_it_and_is_kept_exactly() {
        SkillsGatewayProperties.Retention retention = with(Duration.ofDays(90));

        assertThat(retention.ledgerTrimEnabled()).isTrue();
        assertThat(retention.ledgerMaxAge()).isEqualTo(Duration.ofDays(90));
    }
}
