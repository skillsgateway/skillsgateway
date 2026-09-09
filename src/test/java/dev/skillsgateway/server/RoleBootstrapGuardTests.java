package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.RemovedPropertyGuard;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.roles.RoleBootstrapGuard;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The startup refusal for an estate nobody could administer (GW_AUTH_0026), as a real Spring context
 * that either starts or does not.
 *
 * <p>Removing the enforcement switch trades one failure for another: instead of a gateway anyone can
 * administer, a gateway nobody can. The point of these cases is that the second failure is loud and
 * at deployment time, and that its message is actionable — a refusal nobody can act on is only
 * marginally better than a silent misconfiguration.
 *
 * <p>The refusal for the removed switch itself moved to {@code RemovedPropertyGuardTests}, with the
 * guard.
 */
class RoleBootstrapGuardTests {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(GuardUnderTest.class);

    @Test
    @SVCs({"SVC_GW_AUTH_0026"})
    void a_gateway_with_no_configured_administrator_refuses_to_start() {
        contexts.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has no administrator")
                    // Every way out is named, because an operator reading this is holding a manifest
                    // and needs to know which line to add.
                    .hasMessageContaining("skills-gateway.roles.admins")
                    .hasMessageContaining("skills-gateway.roles.mappings")
                    .hasMessageContaining("skills-gateway.estate.grants")
                    .hasMessageContaining("skills-gateway.dev-insecure-auth");
        });
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0026"})
    void each_configuration_path_that_names_an_administrator_starts_cleanly() {
        contexts.withPropertyValues("skills-gateway.roles.admins=ops")
                .run(context -> assertThat(context).hasNotFailed());

        contexts.withPropertyValues(
                        "skills-gateway.roles.mappings[0].claim-value=gw-admins",
                        "skills-gateway.roles.mappings[0].role=admin")
                .run(context -> assertThat(context).hasNotFailed());

        contexts.withPropertyValues(
                        "skills-gateway.estate.grants[0].principal=ops", "skills-gateway.estate.grants[0].role=admin")
                .run(context -> assertThat(context).hasNotFailed());

        // The escape hatch makes its own principal an admin, so there is nothing to check.
        contexts.withPropertyValues("skills-gateway.dev-insecure-auth=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * A mapping or a declared grant that resolves some <em>other</em> role is not an administrator.
     * Without this case the check could be satisfied by any mapping at all, which would make it
     * assert almost nothing.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0026"})
    void a_configuration_naming_only_a_lesser_role_is_still_no_administrator() {
        contexts.withPropertyValues(
                        "skills-gateway.roles.mappings[0].claim-value=gw-auditors",
                        "skills-gateway.roles.mappings[0].role=auditor")
                .run(context -> assertRefused(context, "has no administrator"));

        contexts.withPropertyValues(
                        "skills-gateway.estate.grants[0].principal=ops",
                        "skills-gateway.estate.grants[0].role=approver",
                        "skills-gateway.estate.grants[0].marketplace=acme")
                .run(context -> assertRefused(context, "has no administrator"));
    }

    private static void assertRefused(AssertableApplicationContext context, String signal) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(signal);
    }

    /** The guard itself, not a stand-in: imported as the component the application scans. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    @Import({RemovedPropertyGuard.class, RoleBootstrapGuard.class})
    static class GuardUnderTest {}
}
