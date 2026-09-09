package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties.ResolutionBudgets;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The resolution budgets are a dial an operator may turn down and not one they may turn off
 * (GW_INGEST_0026).
 *
 * <p>These bounds exist so that no manifest the gateway will look at can exhaust the gateway. A
 * bound settable to any value is not that: it is a control an operator can switch off by typing a
 * large number, and the gateway would then behave exactly as an unbounded resolver while its
 * configuration still claimed a limit. The requirement says the bounds are set <em>by
 * configuration</em>, and one of them says a legitimate marketplace has to be able to raise its own
 * bound without raising every total beside it, so they cannot become constants either.
 *
 * <p>A ceiling keeps both. Lowering is unrestricted, raising works up to the point past which the
 * bound stops defending anything, and the attempt to go past it is clamped and logged rather than
 * refused — a resolution budget is not worth failing a start over, and an operator who reads the
 * log finds the number the gateway is actually using.
 */
class ResolutionBudgetCeilingTests {

    /** Nothing configured: every bound is its default, and no default is itself clamped. */
    @Test
    @SVCs({"SVC_GW_INGEST_0031"})
    void theDefaultsAreWellInsideTheCeilings() {
        ResolutionBudgets budgets = new ResolutionBudgets(null, null, null, null, null, null, null, null, null);

        assertThat(budgets.maxReceivedBytes()).isEqualTo(DataSize.ofMegabytes(50));
        assertThat(budgets.maxInflatedBytes()).isEqualTo(DataSize.ofMegabytes(200));
        assertThat(budgets.maxClosureBytes()).isEqualTo(DataSize.ofMegabytes(500));
        assertThat(budgets.maxInflationRatio()).isEqualTo(100);
        assertThat(budgets.maxObjects()).isEqualTo(20000);
        assertThat(budgets.maxBlobBytes()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(budgets.maxTreeDepth()).isEqualTo(32);
        assertThat(budgets.maxRedirects()).isEqualTo(3);
        assertThat(budgets.deadline()).isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * Turning a bound down is always honoured. This is the case the requirement's "by
     * configuration" is really about, and the case three existing suites depend on: they shrink a
     * bound so a small fixture can exceed it, rather than building a fixture large enough to
     * exceed a production default.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0031"})
    void everyBoundCanBeLowered() {
        ResolutionBudgets budgets = new ResolutionBudgets(
                DataSize.ofKilobytes(1),
                DataSize.ofKilobytes(2),
                DataSize.ofKilobytes(3),
                2,
                10,
                DataSize.ofKilobytes(4),
                1,
                0,
                Duration.ofSeconds(1));

        assertThat(budgets.maxReceivedBytes()).isEqualTo(DataSize.ofKilobytes(1));
        assertThat(budgets.maxInflatedBytes()).isEqualTo(DataSize.ofKilobytes(2));
        assertThat(budgets.maxClosureBytes()).isEqualTo(DataSize.ofKilobytes(3));
        assertThat(budgets.maxInflationRatio()).isEqualTo(2);
        assertThat(budgets.maxObjects()).isEqualTo(10);
        assertThat(budgets.maxBlobBytes()).isEqualTo(DataSize.ofKilobytes(4));
        assertThat(budgets.maxTreeDepth()).isEqualTo(1);
        assertThat(budgets.maxRedirects()).isZero();
        assertThat(budgets.deadline()).isEqualTo(Duration.ofSeconds(1));
    }

    /**
     * A raise inside the ceiling is honoured too, which is the half that keeps this a policy rather
     * than an obstacle: a marketplace that legitimately outgrows a default has somewhere to go.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0031"})
    void aBoundCanStillBeRaisedForAMarketplaceThatOutgrewTheDefault() {
        ResolutionBudgets budgets = new ResolutionBudgets(
                DataSize.ofMegabytes(200),
                DataSize.ofMegabytes(900),
                DataSize.ofGigabytes(2),
                500,
                100_000,
                DataSize.ofMegabytes(64),
                64,
                5,
                Duration.ofMinutes(20));

        assertThat(budgets.maxBlobBytes())
                .as("GW_INGEST_0026.6 requires this one to be raisable on its own")
                .isEqualTo(DataSize.ofMegabytes(64));
        assertThat(budgets.maxReceivedBytes()).isEqualTo(DataSize.ofMegabytes(200));
        assertThat(budgets.maxInflatedBytes()).isEqualTo(DataSize.ofMegabytes(900));
        assertThat(budgets.maxClosureBytes()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(budgets.maxInflationRatio()).isEqualTo(500);
        assertThat(budgets.maxObjects()).isEqualTo(100_000);
        assertThat(budgets.maxTreeDepth()).isEqualTo(64);
        assertThat(budgets.maxRedirects()).isEqualTo(5);
        assertThat(budgets.deadline()).isEqualTo(Duration.ofMinutes(20));
    }

    /**
     * And the case the ceiling exists for: every bound set to something that would disable it.
     *
     * <p>Each is asserted separately rather than as "not what was asked for", because a clamp that
     * silently applied one ceiling to the wrong bound would be invisible to a weaker assertion —
     * and the whole point of nine sub-requirements is that these defend against different things.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0031"})
    void noBoundCanBeRaisedPastWhatTheGatewayWillDefend() {
        ResolutionBudgets budgets = new ResolutionBudgets(
                DataSize.ofGigabytes(100),
                DataSize.ofGigabytes(100),
                DataSize.ofGigabytes(100),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                DataSize.ofGigabytes(100),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Duration.ofDays(1));

        assertThat(budgets.maxReceivedBytes()).isEqualTo(DataSize.ofMegabytes(500));
        assertThat(budgets.maxInflatedBytes()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(budgets.maxClosureBytes()).isEqualTo(DataSize.ofGigabytes(5));
        assertThat(budgets.maxInflationRatio()).isEqualTo(1000);
        assertThat(budgets.maxObjects()).isEqualTo(200_000);
        assertThat(budgets.maxBlobBytes()).isEqualTo(DataSize.ofMegabytes(100));
        assertThat(budgets.maxTreeDepth()).isEqualTo(256);
        assertThat(budgets.maxRedirects()).isEqualTo(10);
        assertThat(budgets.deadline()).isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * The ceilings are above the defaults, checked rather than assumed. A ceiling that slipped
     * below its default would silently lower the shipped bound for every deployment — a change to
     * what the gateway accepts, made by a constant nobody was looking at.
     */
    @Test
    @SVCs({"SVC_GW_INGEST_0031"})
    void everyCeilingIsAboveItsDefault() {
        ResolutionBudgets defaults = new ResolutionBudgets(null, null, null, null, null, null, null, null, null);
        ResolutionBudgets ceilings = new ResolutionBudgets(
                DataSize.ofGigabytes(100),
                DataSize.ofGigabytes(100),
                DataSize.ofGigabytes(100),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                DataSize.ofGigabytes(100),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Duration.ofDays(1));

        assertThat(ceilings.maxReceivedBytes().toBytes())
                .isGreaterThan(defaults.maxReceivedBytes().toBytes());
        assertThat(ceilings.maxInflatedBytes().toBytes())
                .isGreaterThan(defaults.maxInflatedBytes().toBytes());
        assertThat(ceilings.maxClosureBytes().toBytes())
                .isGreaterThan(defaults.maxClosureBytes().toBytes());
        assertThat(ceilings.maxInflationRatio()).isGreaterThan(defaults.maxInflationRatio());
        assertThat(ceilings.maxObjects()).isGreaterThan(defaults.maxObjects());
        assertThat(ceilings.maxBlobBytes().toBytes())
                .isGreaterThan(defaults.maxBlobBytes().toBytes());
        assertThat(ceilings.maxTreeDepth()).isGreaterThan(defaults.maxTreeDepth());
        assertThat(ceilings.maxRedirects()).isGreaterThan(defaults.maxRedirects());
        assertThat(ceilings.deadline()).isGreaterThan(defaults.deadline());
    }
}
