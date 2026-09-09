package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.config.RemovedProperties.Disposition;
import dev.skillsgateway.server.config.RemovedProperties.Removal;
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
import org.springframework.mock.env.MockEnvironment;

/**
 * A manifest that still sets a property the gateway removed (GW_AUTH_0027), as a real Spring context
 * that either starts or does not.
 *
 * <p>The cases that matter are the two ways of being wrong about a removal: honouring a name that
 * no longer means anything, and refusing one whose absence costs nothing. The first is what the
 * refusal is for; the second is why every removal carries a disposition instead of the guard having
 * one policy.
 */
class RemovedPropertyGuardTests {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(GuardUnderTest.class);

    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
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
    @SVCs({"SVC_GW_AUTH_0027"})
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
    @SVCs({"SVC_GW_AUTH_0027"})
    void the_removed_property_is_refused_even_when_there_is_also_no_administrator() {
        contexts.withPropertyValues("skills-gateway.roles.enabled=false")
                .run(context -> assertRefused(context, "was removed"));
    }

    /**
     * And that precedence is wiring, not luck.
     *
     * <p>Both guards throw from their constructors, so which message an operator sees is whichever
     * bean the container built first — and it built them in the order that happens to be right,
     * which is why the case above passes with or without the dependency. Taking the removed-property
     * guard as a constructor parameter is what makes the order a fact; this is the assertion that
     * notices if somebody deletes the parameter as unused, because nothing observable would.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
    void the_bootstrap_guard_is_constructed_after_this_one() {
        assertThat(RoleBootstrapGuard.class.getDeclaredConstructors())
                .as("a stale manifest has to be reported before an estate nobody can administer")
                .singleElement()
                .satisfies(constructor ->
                        assertThat(constructor.getParameterTypes()).contains(RemovedPropertyGuard.class));
    }

    /**
     * The revocation-freshness knob, refused for the same reason as the enforcement switch.
     *
     * <p>It set how long a replica could keep serving a reference map it had already read, so a
     * deployment that set it was asking for a longer window in which a revoked snapshot stays
     * advertised. Ignoring it would shorten that window silently — the right behaviour reached by
     * the wrong route, with the operator still believing the value they wrote was in force.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
    void the_removed_revocation_freshness_knob_is_refused() {
        contexts.withPropertyValues(
                        "skills-gateway.roles.admins=ops",
                        "skills-gateway.storage.object-store.cache.ref-freshness=10s")
                .run(context -> assertRefused(context, "ref-freshness"));
    }

    /** A clean manifest starts, which is the case that proves the guard is looking at all. */
    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
    void a_deployment_setting_nothing_removed_starts() {
        contexts.withPropertyValues("skills-gateway.roles.admins=ops")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The two dispositions, on the mechanism rather than on today's single entry.
     *
     * <p>A removal whose only cost is latency must not stop a start: converting a leftover line in
     * a manifest into an outage is a worse trade than a log line. Asserted here so that the choice
     * exists and works before anything relies on it — the entry that needs it is a later change,
     * and a disposition first exercised by the change that needs it is a disposition nobody
     * reviewed.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
    void a_removal_that_only_costs_latency_is_ignored_rather_than_refused() {
        MockEnvironment environment = new MockEnvironment().withProperty("skills-gateway.gone.interval", "5s");

        assertThat(RemovedPropertyGuard.check(
                        environment,
                        Map.of("skills-gateway.gone.interval", new Removal(Disposition.WARN, "Nothing polls now."))))
                .as("a start that continues, and says which stale line it continued past")
                .containsExactly("skills-gateway.gone.interval");

        assertThatThrownBy(() -> RemovedPropertyGuard.check(
                        environment,
                        Map.of("skills-gateway.gone.interval", new Removal(Disposition.REFUSE, "Nothing polls now."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.gone.interval")
                .hasMessageContaining("Nothing polls now.");
    }

    /**
     * Every stale line at once. An operator upgrading across several versions has more than one,
     * and a guard that stops at the first turns one edit into a sequence of failed starts.
     */
    @Test
    @SVCs({"SVC_GW_AUTH_0027"})
    void every_refusal_is_reported_together() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("skills-gateway.gone.one", "x")
                .withProperty("skills-gateway.gone.two", "y");

        assertThatThrownBy(() -> RemovedPropertyGuard.check(
                        environment,
                        Map.of(
                                "skills-gateway.gone.one", new Removal(Disposition.REFUSE, "First."),
                                "skills-gateway.gone.two", new Removal(Disposition.REFUSE, "Second."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.gone.one")
                .hasMessageContaining("skills-gateway.gone.two");
    }

    private static void assertRefused(AssertableApplicationContext context, String signal) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(signal);
    }

    /**
     * Both guards, not a stand-in: the removed-property refusal has to be shown taking precedence
     * over the administrator check, which needs the pair the application actually scans.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillsGatewayProperties.class)
    @Import({RemovedPropertyGuard.class, RoleBootstrapGuard.class})
    static class GuardUnderTest {}
}
