package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.roles.RoleBootstrapGuard;
import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The two startup refusals that replace the switch (GW_0139, GW_0140), each as a real Spring context
 * that either starts or does not.
 *
 * <p>Removing the enforcement switch trades one failure for another: instead of a gateway anyone can
 * administer, a gateway nobody can. The point of these cases is that the second failure is loud and
 * at deployment time, and that its message is actionable — a refusal nobody can act on is only
 * marginally better than a silent misconfiguration.
 */
class RoleBootstrapGuardTests {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(GuardUnderTest.class);

    @Test
    @SVCs({"SVC_GW_0139"})
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
    @SVCs({"SVC_GW_0139"})
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
    @SVCs({"SVC_GW_0139"})
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

    @Test
    @SVCs({"SVC_GW_0140"})
    void the_removed_property_is_refused_in_every_spelling() {
        // false is the dangerous one: ignoring it would reverse an operator's explicit intention
        // that the gateway have no authorization.
        contexts.withPropertyValues("skills-gateway.roles.admins=ops", "skills-gateway.roles.enabled=false")
                .run(context -> assertRefused(context, "was removed"));

        // true is harmless in effect and still refused, because a property that is read and a
        // property that is ignored must not look the same from inside a deployment.
        contexts.withPropertyValues("skills-gateway.roles.admins=ops", "skills-gateway.roles.enabled=true")
                .run(context -> assertRefused(context, "was removed"));
    }

    /**
     * The environment-variable spelling, through a real {@link SystemEnvironmentPropertySource}.
     *
     * <p>Not {@code withPropertyValues}: that installs literal property names, so it would prove
     * only that a property called {@code SKILLSGATEWAY_ROLES_ENABLED} is absent. The claim worth
     * checking is the production one — an operator who set the environment variable a Helm chart or
     * a container runtime would set is refused by the same single check, rather than by a second
     * list of spellings that has to be kept in step with the first.
     */
    @Test
    @SVCs({"SVC_GW_0140"})
    void the_removed_property_is_refused_as_an_environment_variable() {
        contexts.withPropertyValues("skills-gateway.roles.admins=ops")
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("SKILLSGATEWAY_ROLES_ENABLED", "false"))))
                .run(context -> assertRefused(context, "was removed"));
    }

    /** The removed property is refused before the bootstrap check, so its message is the one seen. */
    @Test
    @SVCs({"SVC_GW_0140"})
    void the_removed_property_is_refused_even_when_there_is_also_no_administrator() {
        contexts.withPropertyValues("skills-gateway.roles.enabled=false")
                .run(context -> assertRefused(context, "was removed"));
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
    @Import(RoleBootstrapGuard.class)
    static class GuardUnderTest {}
}
