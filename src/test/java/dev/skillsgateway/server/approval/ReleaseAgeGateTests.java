package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cooling-off rule itself (GW_0073), evaluated as the pure function of
 * {@code (firstIngestedAt, now, minimum)} that it is.
 *
 * <p>The boundary is the whole point of testing it this way. "Eligible once the age has been
 * reached" and "eligible once the age has been exceeded" differ by one instant, and no test that
 * races a real clock can tell them apart; here the exact instant is an argument.
 */
class ReleaseAgeGateTests {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-18T09:00:00Z");
    private static final Duration MINIMUM = Duration.ofHours(24);

    @Test
    @SVCs({"SVC_GW_0073"})
    void eligibility_turns_over_exactly_at_the_boundary_instant() {
        Instant boundary = FIRST_SEEN.plus(MINIMUM);

        ReleaseAgeGate.Eligibility at = ReleaseAgeGate.evaluate(1L, FIRST_SEEN, boundary, MINIMUM);
        assertThat(at.eligible()).as("eligible at exactly first-seen + minimum").isTrue();
        assertThat(at.remainingSeconds()).isZero();
        assertThat(at.eligibleAt()).isEqualTo(boundary);
        assertThat(at.ageSeconds()).isEqualTo(MINIMUM.toSeconds());

        ReleaseAgeGate.Eligibility justBefore =
                ReleaseAgeGate.evaluate(1L, FIRST_SEEN, boundary.minusNanos(1), MINIMUM);
        assertThat(justBefore.eligible())
                .as("not eligible one nanosecond before the boundary")
                .isFalse();
        assertThat(justBefore.eligibleAt()).isEqualTo(boundary);

        // The remaining time counts down, and is reported against the same deadline throughout.
        ReleaseAgeGate.Eligibility halfway =
                ReleaseAgeGate.evaluate(1L, FIRST_SEEN, FIRST_SEEN.plus(Duration.ofHours(18)), MINIMUM);
        assertThat(halfway.eligible()).isFalse();
        assertThat(halfway.remainingSeconds()).isEqualTo(Duration.ofHours(6).toSeconds());
        assertThat(halfway.minimumReleaseAgeSeconds()).isEqualTo(MINIMUM.toSeconds());
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void a_zero_minimum_is_no_gate_at_all() {
        ReleaseAgeGate.Eligibility fresh = ReleaseAgeGate.evaluate(1L, FIRST_SEEN, FIRST_SEEN, Duration.ZERO);

        assertThat(fresh.eligible()).isTrue();
        assertThat(fresh.remainingSeconds()).isZero();
        assertThat(fresh.minimumReleaseAgeSeconds()).isZero();
        assertThat(fresh.eligibleAt()).isEqualTo(FIRST_SEEN);
    }

    @Test
    @SVCs({"SVC_GW_0073"})
    void the_remaining_time_is_rendered_as_a_reviewer_reads_it() {
        assertThat(ReleaseAgeGate.format(Duration.ofHours(52))).isEqualTo("2d 4h");
        assertThat(ReleaseAgeGate.format(Duration.ofDays(3))).isEqualTo("3d");
        assertThat(ReleaseAgeGate.format(Duration.ofMinutes(150))).isEqualTo("2h 30m");
        assertThat(ReleaseAgeGate.format(Duration.ofMinutes(45))).isEqualTo("45m");
        assertThat(ReleaseAgeGate.format(Duration.ofSeconds(30))).isEqualTo("30s");
        assertThat(ReleaseAgeGate.format(Duration.ZERO)).isEqualTo("0s");
    }
}
