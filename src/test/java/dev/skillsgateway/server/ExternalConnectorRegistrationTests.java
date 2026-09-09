package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.ConnectorToggleService;
import dev.skillsgateway.server.vetting.ExternalVettingConfiguration;
import dev.skillsgateway.server.vetting.LicenseScanConnector;
import dev.skillsgateway.server.vetting.PromptInjectionConnector;
import dev.skillsgateway.server.vetting.SecretScanConnector;
import dev.skillsgateway.server.vetting.SkillConformanceConnector;
import dev.skillsgateway.server.vetting.VettingConnector;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verification that an operator-configured external connector is bound from configuration and joins
 * the ordered chain (GW_VETTING_0024). The credential uses a {@code ${...}} placeholder to prove
 * placeholder resolution — a literal in a manifest is exactly what the write-only contract exists to
 * avoid.
 *
 * <p>The external chain is a deployment decision resolved while the context is still being built:
 * {@code ExternalVettingConnectorRegistrar} is an {@code ImportBeanDefinitionRegistrar} that binds
 * {@code skills-gateway.vetting.external[*]} straight off the {@code Environment} and registers a
 * bean per entry. So this cannot be varied inside a running gateway's context — but neither does it
 * need one. An {@link ApplicationContextRunner} refreshes a real context containing the registrar,
 * the built-in connectors and the service that orders them, which is the entire population the
 * assertions read, and it starts no web server, no datasource and no PostgreSQL container.
 *
 * <p>What that gives up is the component scan: the built-ins are named here rather than discovered.
 * The chain as production assembles it stays verified where it always was, in {@code VettingTests}
 * and {@code ConnectorToggleTests}, which run it against real content in the shared context.
 */
class ExternalConnectorRegistrationTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ChainUnderTest.class, ExternalVettingConfiguration.class)
            .withPropertyValues(
                    "skills-gateway.vetting.external[0].name=llm-review",
                    "skills-gateway.vetting.external[0].url=http://127.0.0.1:59321/vet",
                    "skills-gateway.vetting.external[0].order=1",
                    "skills-gateway.vetting.external[0].version=7",
                    "skills-gateway.vetting.external[0].description=IT Security LLM review endpoint",
                    "skills-gateway.vetting.external[0].token=${SKILLS_GATEWAY_TEST_LLM_TOKEN:placeholder-value}");

    /**
     * The built-in connectors and the service that orders them. The remaining collaborators are
     * mocked because neither {@code connectors()} nor {@code chainIdentity()} reaches them — they
     * are the run machinery, and nothing here runs the chain.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    static class ChainUnderTest {

        @Bean
        SecretScanConnector secretScanConnector() {
            return new SecretScanConnector();
        }

        @Bean
        PromptInjectionConnector promptInjectionConnector() {
            return new PromptInjectionConnector();
        }

        @Bean
        LicenseScanConnector licenseScanConnector(SkillsGatewayProperties properties) {
            return new LicenseScanConnector(properties);
        }

        @Bean
        SkillConformanceConnector skillConformanceConnector(SkillsGatewayProperties properties) {
            return new SkillConformanceConnector(properties);
        }

        @Bean
        VettingService vettingService(List<VettingConnector> connectors, SkillsGatewayProperties properties) {
            return new VettingService(
                    connectors,
                    mock(VettingRepository.class),
                    mock(GitStorage.class),
                    mock(AdminAuditLogger.class),
                    mock(WebhookService.class),
                    mock(WaiverService.class),
                    mock(ConnectorToggleService.class),
                    properties);
        }
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0024"})
    void aConfiguredExternalConnectorJoinsTheChainInItsConfiguredPosition() {
        contexts.run(context -> {
            VettingService vettingService = context.getBean(VettingService.class);

            // order=1 sits ahead of every built-in (whose orders start at 100), so it is first.
            VettingConnector first = vettingService.connectors().getFirst();
            assertThat(first.name()).isEqualTo("llm-review");
            assertThat(first.version()).isEqualTo("7");
            assertThat(first.description()).contains("IT Security LLM review endpoint");

            // The built-ins are still all present: the external connector is added, not a
            // replacement.
            assertThat(vettingService.connectors())
                    .extracting(VettingConnector::name)
                    .contains("secret-scan", "prompt-injection", "license-scan", "llm-review");

            // The chain identity — stamped on every run (GW_VETTING_0012) — now names the external connector
            // and its version, so a run is attributable to the exact external chain that produced it.
            assertThat(vettingService.chainIdentity()).startsWith("llm-review@7,");
        });
    }
}
