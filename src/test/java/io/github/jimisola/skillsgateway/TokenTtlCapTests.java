package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jimisola.skillsgateway.auth.TokenService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The configured lifetime cap (GW_0065) in force — its own context, because the cap is a
 * deployment decision and the shared fixture's tokens are deliberately unlimited.
 */
@TestPropertySource(properties = "skills-gateway.tokens.max-ttl=30d")
class TokenTtlCapTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0065"})
    void a_lifetime_beyond_the_cap_is_refused_rather_than_shortened() {
        // Within the cap: accepted, and the requested deadline is stored untouched. Microsecond
        // precision: TIMESTAMPTZ stores micros, and the round-trip equality must not depend on
        // the platform clock's resolution (nanos on Linux, micros on macOS).
        Instant requested = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS);
        TokenService.IssuedToken within = tokenService.create("alice", "short", List.of(), requested);
        assertThat(within.expiresAt()).isEqualTo(requested);

        // Beyond the cap, or with no expiry at all under a cap: refused, never clamped.
        assertThatThrownBy(() -> tokenService.create(
                        "alice", "long", List.of(), Instant.now().plus(Duration.ofDays(60))))
                .isInstanceOf(TokenService.InvalidTokenRequestException.class);
        assertThatThrownBy(() -> tokenService.create("alice", "forever", List.of(), null))
                .isInstanceOf(TokenService.InvalidTokenRequestException.class);

        // Rotation keeps working under the cap: same deadline, no re-validation surprise.
        assertThatCode(() -> tokenService.rotate(within.id(), "alice")).doesNotThrowAnyException();
    }
}
