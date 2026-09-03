package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties.ResolutionBudgets;
import io.github.reqstool.annotations.SVCs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * GW_0158 as a pure accumulator: every bound is checked at the boundary and one past it, because a
 * budget that is off by one either refuses legitimate content or admits the content it exists to
 * refuse, and only testing both sides tells them apart.
 */
class ResolutionBudgetTests {

    private static final Instant START = Instant.parse("2026-09-03T10:00:00Z");

    private static ResolutionBudgets limits() {
        return new ResolutionBudgets(
                DataSize.ofBytes(1000), // received per source
                DataSize.ofBytes(4000), // inflated per source
                DataSize.ofBytes(6000), // inflated per closure
                10, // inflation ratio
                50, // objects per source
                DataSize.ofBytes(500), // largest blob
                4, // tree depth
                3, // redirects
                Duration.ofSeconds(30));
    }

    private static ResolutionBudget budget() {
        return new ResolutionBudget(limits(), Clock.fixed(START, ZoneOffset.UTC));
    }

    /** A clock the test moves, so the deadline can be crossed without waiting for it. */
    private static final class MovableClock extends Clock {

        private Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static ResolutionBudget.Measurement measurement(
            long received, long inflated, long objects, long largestBlob, int depth) {
        return new ResolutionBudget.Measurement(received, inflated, objects, largestBlob, depth);
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void content_inside_every_bound_is_accepted() {
        assertThat(budget().accept("tools", measurement(1000, 4000, 50, 500, 4)))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_over_the_received_byte_bound_is_refused() {
        assertThat(budget().accept("tools", measurement(1001, 10, 1, 10, 1)))
                .isNotNull()
                .contains("received");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_over_the_inflated_byte_bound_is_refused() {
        assertThat(budget().accept("tools", measurement(1000, 4001, 1, 10, 1)))
                .isNotNull()
                .contains("uncompressed");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_over_the_inflation_ratio_is_refused_even_when_both_byte_bounds_hold() {
        // 30 received bytes expanding to 3000 is a hundredfold, well inside both byte bounds and
        // exactly the shape of a pack bomb. This is why the ratio is a bound of its own.
        assertThat(budget().accept("tools", measurement(30, 3000, 1, 10, 1)))
                .isNotNull()
                .contains("ratio");
        assertThat(budget().accept("tools", measurement(300, 3000, 1, 10, 1))).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void the_ratio_is_not_judged_below_the_floor_where_the_absolute_bound_already_caps_the_damage() {
        // A 900-byte file arriving in 9 bytes is a hundredfold expansion and a threat to nothing:
        // the per-source inflated bound caps it. Refusing it would refuse ordinary repositories,
        // where a small file of repeated text compresses just as hard.
        assertThat(budget().accept("tools", measurement(9, 900, 1, 500, 1))).isNull();
        // Just over the floor (a quarter of the 4000-byte inflated bound) the ratio applies again.
        assertThat(budget().accept("tools", measurement(10, 1000, 1, 500, 1)))
                .isNotNull()
                .contains("ratio");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_over_the_object_count_is_refused() {
        assertThat(budget().accept("tools", measurement(100, 100, 51, 10, 1)))
                .isNotNull()
                .contains("objects");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_with_a_file_over_the_blob_bound_is_refused() {
        assertThat(budget().accept("tools", measurement(100, 600, 2, 501, 1)))
                .isNotNull()
                .contains("file");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void a_source_with_a_tree_deeper_than_the_bound_is_refused() {
        assertThat(budget().accept("tools", measurement(100, 100, 2, 10, 5)))
                .isNotNull()
                .contains("depth");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void the_closure_bound_accumulates_across_sources() {
        ResolutionBudget budget = budget();

        assertThat(budget.accept("one", measurement(400, 3500, 2, 10, 1))).isNull();
        // 3500 + 3500 is over the 6000-byte closure bound although neither source is over its own.
        assertThat(budget.accept("two", measurement(400, 3500, 2, 10, 1)))
                .isNotNull()
                .contains("manifest");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void the_violation_names_the_plugin_and_the_bound_that_was_exceeded() {
        assertThat(budget().accept("tools", measurement(5000, 10, 1, 10, 1)))
                .contains("tools")
                .contains("1000");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void the_deadline_is_not_expired_before_it_passes_and_is_after() {
        MovableClock clock = new MovableClock();
        ResolutionBudget budget = new ResolutionBudget(limits(), clock);

        assertThat(budget.expired()).isNull();
        clock.now = START.plusSeconds(30);
        assertThat(budget.expired()).isNull();
        clock.now = START.plusSeconds(31);
        assertThat(budget.expired()).isNotNull().contains("deadline");
    }

    @Test
    @SVCs({"SVC_GW_0158"})
    void the_received_byte_bound_is_published_so_a_transfer_can_be_stopped_rather_than_measured() {
        // The stream has to refuse mid-transfer; measuring after the fact would mean the content
        // the bound exists to refuse has already been received.
        assertThat(budget().maxReceivedBytes()).isEqualTo(1000L);
    }
}
