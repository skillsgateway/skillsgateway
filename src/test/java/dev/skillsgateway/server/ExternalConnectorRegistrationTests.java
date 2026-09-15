package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.VetterToggle;
import dev.skillsgateway.server.vetting.VetterToggleRepository;
import dev.skillsgateway.server.vetting.VetterToggleService;
import dev.skillsgateway.server.vetting.ExternalVettingConfiguration;
import dev.skillsgateway.server.vetting.LicenseScanVetter;
import dev.skillsgateway.server.vetting.PromptInjectionVetter;
import dev.skillsgateway.server.vetting.SecretScanVetter;
import dev.skillsgateway.server.vetting.SkillConformanceVetter;
import dev.skillsgateway.server.vetting.Verdict;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.Vetter;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verification that an operator-configured external vetter is bound from configuration and joins
 * the ordered chain (GW_VETTING_0024). The credential uses a {@code ${...}} placeholder to prove
 * placeholder resolution — a literal in a manifest is exactly what the write-only contract exists to
 * avoid.
 *
 * <p>The external chain is a deployment decision resolved while the context is still being built:
 * {@code ExternalVettingConnectorRegistrar} is an {@code ImportBeanDefinitionRegistrar} that binds
 * {@code skills-gateway.vetting.external[*]} straight off the {@code Environment} and registers a
 * bean per entry. So this cannot be varied inside a running gateway's context — but neither does it
 * need one. An {@link ApplicationContextRunner} refreshes a real context containing the registrar,
 * the built-in vetters and the service that orders them, which is the entire population the
 * assertions read, and it starts no web server, no datasource and no PostgreSQL container.
 *
 * <p>What that gives up is the component scan: the built-ins are named here rather than discovered.
 * The chain as production assembles it stays verified where it always was, in {@code VettingTests}
 * and {@code VetterToggleTests}, which run it against real content in the shared context.
 *
 * <p>The same context is also where the administrative on/off switch meets an external vetter
 * (GW_VETTING_0029.1, GW_VETTING_0029.2): the switch's known-vetter set is the injected {@code
 * List<Vetter>}, which an external vetter joins like any other, so this is the one
 * harness in which both an external vetter and the switch exist together.
 */
class ExternalConnectorRegistrationTests {

    private static final String MARKETPLACE = "external-toggle";
    private static final long MARKETPLACE_ID = 42L;
    private static final long SNAPSHOT_ID = 7L;
    private static final long RUN_ID = 99L;

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

    @TempDir
    Path quarantine;

    private String sha;

    @BeforeEach
    void commitQuarantineSnapshot() throws Exception {
        Files.createDirectories(quarantine.resolve("skills"));
        Files.writeString(quarantine.resolve("skills/example.md"), "# Example skill\n", StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(quarantine.toFile()).call()) {
            git.add().addFilepattern(".").call();
            sha = git.commit()
                    .setMessage("snapshot")
                    .setSign(false)
                    .setAuthor("test", "test@example.invalid")
                    .setCommitter("test", "test@example.invalid")
                    .call()
                    .name();
        }
    }

    /**
     * The built-in vetters, the service that orders them, and the real on/off switch over that
     * same vetter list. The persistence collaborators are mocks exposed as beans so a test can
     * stub and read them: what is under test is the switch's rule and the chain's honouring of it,
     * not the SQL underneath.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    static class ChainUnderTest {

        @Bean
        VetterToggleRepository vetterToggleRepository() {
            return mock(VetterToggleRepository.class);
        }

        @Bean
        MarketplaceRepository marketplaceRepository() {
            return mock(MarketplaceRepository.class);
        }

        @Bean
        AdminAuditLogger auditLogger() {
            return mock(AdminAuditLogger.class);
        }

        @Bean
        VettingRepository vettingRepository() {
            return mock(VettingRepository.class);
        }

        @Bean
        GitStorage gitStorage() {
            return mock(GitStorage.class);
        }

        @Bean
        VetterToggleService vetterToggleService(
                VetterToggleRepository repository,
                MarketplaceRepository marketplaces,
                AdminAuditLogger auditLogger,
                List<Vetter> vetters) {
            return new VetterToggleService(repository, marketplaces, auditLogger, vetters);
        }

        @Bean
        SecretScanVetter secretScanVetter() {
            return new SecretScanVetter();
        }

        @Bean
        PromptInjectionVetter promptInjectionVetter() {
            return new PromptInjectionVetter();
        }

        @Bean
        LicenseScanVetter licenseScanVetter(SkillsGatewayProperties properties) {
            return new LicenseScanVetter(properties);
        }

        @Bean
        SkillConformanceVetter skillConformanceVetter(SkillsGatewayProperties properties) {
            return new SkillConformanceVetter(properties);
        }

        @Bean
        VettingService vettingService(
                List<Vetter> vetters,
                VettingRepository vettingRepository,
                GitStorage storage,
                AdminAuditLogger auditLogger,
                VetterToggleService toggleService,
                SkillsGatewayProperties properties) {
            return new VettingService(
                    vetters,
                    vettingRepository,
                    storage,
                    auditLogger,
                    mock(WebhookService.class),
                    mock(WaiverService.class),
                    toggleService,
                    properties);
        }
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0024"})
    void aConfiguredExternalVetterJoinsTheChainInItsConfiguredPosition() {
        contexts.run(context -> {
            VettingService vettingService = context.getBean(VettingService.class);

            // order=1 sits ahead of every built-in (whose orders start at 100), so it is first.
            Vetter first = vettingService.vetters().getFirst();
            assertThat(first.name()).isEqualTo("llm-review");
            assertThat(first.version()).isEqualTo("7");
            assertThat(first.description()).contains("IT Security LLM review endpoint");

            // The built-ins are still all present: the external vetter is added, not a
            // replacement.
            assertThat(vettingService.vetters())
                    .extracting(Vetter::name)
                    .contains("secret-scan", "prompt-injection", "license-scan", "llm-review");

            // The chain identity — stamped on every run (GW_VETTING_0012) — now names the external vetter
            // and its version, so a run is attributable to the exact external chain that produced it.
            assertThat(vettingService.chainIdentity()).startsWith("llm-review@7,");
        });
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0029.1", "SVC_GW_VETTING_0029.2"})
    void anExternalVetterIsSubjectToTheSameSwitchAsABuiltIn() {
        contexts.run(context -> {
            VetterToggleService toggles = context.getBean(VetterToggleService.class);
            VetterToggleRepository toggleRepository = context.getBean(VetterToggleRepository.class);
            MarketplaceRepository marketplaces = context.getBean(MarketplaceRepository.class);
            VettingRepository vettingRepository = context.getBean(VettingRepository.class);
            GitStorage storage = context.getBean(GitStorage.class);
            VettingService vettingService = context.getBean(VettingService.class);

            given(marketplaces.findByName(MARKETPLACE)).willReturn(Optional.of(marketplace()));

            // The switch accepts the external vetter's name for one marketplace: its
            // known-vetter set is the chain, not a list of built-ins.
            toggles.set("llm-review", MARKETPLACE, false, "answers too slowly for this marketplace", "root");
            verify(toggleRepository)
                    .set("llm-review", MARKETPLACE_ID, false, "answers too slowly for this marketplace", "root");

            // A name no vetter in the chain carries is still refused, and the refusal names the
            // external vetter among the ones that could have been meant.
            assertThatThrownBy(() -> toggles.set("lm-review", MARKETPLACE, false, null, "root"))
                    .hasMessageContaining("unknown vetter 'lm-review'")
                    .hasMessageContaining("the configured vetters are")
                    .hasMessageContaining("llm-review");

            // And a chain run for that marketplace honours the setting exactly as it does for a
            // built-in: the vetter is skipped and a distinct disabled verdict recorded in its
            // place. Nothing listens on the configured URL, so a vetter that had run would have
            // produced an error verdict — DISABLED is evidence it was never called.
            given(toggleRepository.find("llm-review", MARKETPLACE_ID))
                    .willReturn(Optional.of(
                            new VetterToggle(1L, "llm-review", MARKETPLACE_ID, false, null, "root", Instant.now())));
            given(storage.quarantine(MARKETPLACE)).willReturn(quarantineRepository());
            given(vettingRepository.startRun(eq(SNAPSHOT_ID), any(), any())).willReturn(RUN_ID);

            // Re-vetting an approved snapshot: a plain chain run, with no approval-pending
            // announcement to stub around.
            vettingService.run(snapshot(), MARKETPLACE, VettingRepository.TRIGGER_REVET_MANUAL);

            ArgumentCaptor<Verdict> verdict = ArgumentCaptor.forClass(Verdict.class);
            // Position 0: order=1 puts the external vetter ahead of every built-in.
            verify(vettingRepository).recordVerdict(eq(RUN_ID), eq("llm-review"), eq(0), verdict.capture());
            assertThat(verdict.getValue().state()).isEqualTo(VerdictState.DISABLED);
        });
    }

    private Repository quarantineRepository() throws Exception {
        return new FileRepositoryBuilder()
                .setGitDir(quarantine.resolve(".git").toFile())
                .build();
    }

    private static Marketplace marketplace() {
        return new Marketplace(
                MARKETPLACE_ID,
                MARKETPLACE,
                "https://example.invalid/marketplace.git",
                Instant.now(),
                "root",
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null,
                null,
                null,
                null,
                Marketplace.SYNC_ON_DEMAND,
                null);
    }

    private Snapshot snapshot() {
        return new Snapshot(
                SNAPSHOT_ID,
                MARKETPLACE_ID,
                sha,
                sha,
                Snapshot.APPROVED,
                null,
                Instant.now(),
                "root",
                "root",
                Instant.now(),
                null,
                null,
                null,
                null,
                null);
    }
}
