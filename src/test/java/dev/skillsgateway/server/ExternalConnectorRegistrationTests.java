package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.vetting.VettingConnector;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verification that an operator-configured external connector is bound from configuration and joins
 * the ordered chain (GW_0142). Its own Spring context via {@link TestPropertySource}: the external
 * chain is a deployment decision resolved at startup, so it cannot be varied within the shared
 * context. The credential uses a {@code ${...}} placeholder to prove placeholder resolution — a
 * literal in a manifest is exactly what the write-only contract exists to avoid.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.vetting.external[0].name=llm-review",
            "skills-gateway.vetting.external[0].url=http://127.0.0.1:59321/vet",
            "skills-gateway.vetting.external[0].order=1",
            "skills-gateway.vetting.external[0].version=7",
            "skills-gateway.vetting.external[0].description=IT Security LLM review endpoint",
            "skills-gateway.vetting.external[0].token=${SKILLS_GATEWAY_TEST_LLM_TOKEN:placeholder-value}"
        })
class ExternalConnectorRegistrationTests extends AbstractGatewayTest {

    @Autowired
    private VettingService vettingService;

    @Test
    @SVCs({"SVC_GW_0142"})
    void aConfiguredExternalConnectorJoinsTheChainInItsConfiguredPosition() {
        // order=1 sits ahead of every built-in (whose orders start at 100), so it is first.
        VettingConnector first = vettingService.connectors().getFirst();
        assertThat(first.name()).isEqualTo("llm-review");
        assertThat(first.version()).isEqualTo("7");
        assertThat(first.description()).contains("IT Security LLM review endpoint");

        // The built-ins are still all present: the external connector is added, not a replacement.
        assertThat(vettingService.connectors())
                .extracting(VettingConnector::name)
                .contains("secret-scan", "prompt-injection", "license-scan", "llm-review");

        // The chain identity — stamped on every run (GW_0049) — now names the external connector
        // and its version, so a run is attributable to the exact external chain that produced it.
        assertThat(vettingService.chainIdentity()).startsWith("llm-review@7,");
    }
}
