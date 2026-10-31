package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.roles.RoleBootstrapGuard;
import dev.skillsgateway.server.roles.RolePrincipals;
import io.github.reqstool.annotations.SVCs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

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

    /**
     * A configured administrator is matched exactly (GW_AUTH_0060), so an entry no session can present
     * would start a gateway whose only administrator is nobody. Each must refuse, naming its position
     * and never its value.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedAdministrators")
    @SVCs({"SVC_GW_AUTH_0085"})
    void a_malformed_configured_administrator_refuses_startup_naming_its_position(String fault, List<String> admins) {
        int position = admins.size() - 1;
        withAdmins(contexts, admins).run(context -> assertRefusedAt(context, position, admins.get(position)));
    }

    static Stream<Arguments> malformedAdministrators() {
        return Stream.of(
                Arguments.of("leading space", List.of(" bob")),
                Arguments.of("trailing space", List.of("bob ")),
                Arguments.of("trailing tab", List.of("bob\t")),
                Arguments.of("zero-width space", List.of("bo\u200Bb")),
                Arguments.of("bidi override", List.of("\u202Ebob")),
                Arguments.of("blank", List.of("")),
                Arguments.of("over 255 code points", List.of("b".repeat(RolePrincipals.MAX_LENGTH + 1))),
                Arguments.of("one bad among good", List.of("ops", "alice@example.com", "bob\u200B")));
    }

    /** A malformed entry is a mistake in every mode, so the escape hatch does not skip the check. */
    @Test
    @SVCs({"SVC_GW_AUTH_0085"})
    void a_malformed_configured_administrator_refuses_startup_under_the_escape_hatch_too() {
        withAdmins(contexts.withPropertyValues("skills-gateway.dev-insecure-auth=true"), List.of("bob "))
                .run(context -> assertRefusedAt(context, 0, "bob "));
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0085"})
    void well_formed_configured_administrators_start() {
        List<String> admins =
                List.of("ops", "alice@example.com", "Bob Example", "Zo\u00EB", "b".repeat(RolePrincipals.MAX_LENGTH));
        withAdmins(contexts, admins).run(context -> assertThat(context).hasNotFailed());
        // The comma-delimited form (an environment variable) trims each element as it binds.
        withAdmins(contexts.withPropertyValues("skills-gateway.roles.admins=ops, bob"), List.of())
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** Indexed entries from a map source: TestPropertyValues trims values, which would hide the fault. */
    private static ApplicationContextRunner withAdmins(ApplicationContextRunner runner, List<String> admins) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < admins.size(); i++) {
            entries.put("skills-gateway.roles.admins[%d]".formatted(i), admins.get(i));
        }
        return runner.withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("admins", entries)));
    }

    private static void assertRefusedAt(AssertableApplicationContext context, int position, String value) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.roles.admins[%d] ".formatted(position))
                .hasMessageNotContaining("has no administrator");
        if (!value.strip().isEmpty()) {
            assertThat(rootMessage(failure)).as("the refusal repeats the value").doesNotContain(value.strip());
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
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
