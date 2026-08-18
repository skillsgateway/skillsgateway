package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The headline failure-isolation claim of GW_0087, at the only place it can be claimed honestly:
 * a context whose declared estate contains an invalid entry, booted for real. The application
 * must start and serve; a broken declaration must never take a working estate down.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.estate.marketplaces[0].name=estate-bad",
            "skills-gateway.estate.marketplaces[0].url=ssh://forbidden.invalid/repo.git",
            "skills-gateway.estate.marketplaces[1].name=estate-good",
            "skills-gateway.estate.marketplaces[1].url=file:///tmp/estate-good-upstream"
        })
class EstateStartupFailureTests extends AbstractGatewayTest {

    @Autowired
    private EstateReconciler reconciler;

    @Test
    @SVCs({"SVC_GW_0087"})
    void a_gateway_with_an_invalid_declared_entry_starts_applies_the_rest_and_reports_the_failure() {
        // Reaching this line at all is the claim: the context booted with the broken declaration.
        assertThat(marketplaceRepository.findByName("estate-good")).isPresent();
        assertThat(marketplaceRepository.findByName("estate-bad")).isEmpty();

        EstateReconciliation report = reconciler.lastRun().orElseThrow();
        assertThat(report.trigger()).isEqualTo("startup");
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.entries()).anySatisfy(entry -> {
            assertThat(entry.name()).isEqualTo("estate-bad");
            assertThat(entry.action()).isEqualTo("failed");
            assertThat(entry.detail()).contains("scheme");
        });

        // The failure is loud on the ledger, attributed to the reconciler.
        assertThat(fetchLogRepository.list()).anySatisfy(entry -> {
            assertThat(entry.get("event")).isEqualTo("estate-reconciliation-failed");
            assertThat(entry.get("principal")).isEqualTo(EstateReconciler.ACTOR);
            assertThat(String.valueOf(entry.get("detail"))).contains("estate-bad");
        });
    }
}
