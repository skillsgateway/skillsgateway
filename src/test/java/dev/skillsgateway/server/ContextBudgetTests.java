package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.ReflectionSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * The suite's application-context budget, asserted rather than assumed.
 *
 * <p>Every distinct {@link MergedContextConfiguration} is a separate entry in Spring's test context
 * cache, and therefore a separate live application context — datasource pool, JGit storage,
 * embedded Tomcat. The failure behind #302 was not any single context being expensive; it was that
 * the number of distinct ones grew until the cache stopped evicting and the heap filled. Nothing in
 * the build noticed, because nothing was counting.
 *
 * <p>This is that counter. It resolves each Spring test class's merged context configuration
 * <em>without loading any context</em> — {@code buildMergedContextConfiguration()} is the same call
 * the framework makes to compute the cache key — and fails when the number of distinct keys crosses
 * the budget below.
 *
 * <p>The budget is a ratchet, not a target. Raising it is a deliberate act that says a new posture
 * genuinely cannot be expressed with an existing property set; lowering it is what #305 is for. A
 * failure here is advisory in nature — nothing is broken, the suite has simply grown a new context —
 * so the message names the property sets and points at the issue rather than merely reporting a
 * number.
 */
class ContextBudgetTests {

    /**
     * The number of distinct application contexts the suite is allowed to build.
     *
     * <p>Measured on the change that introduced this test: <b>36</b> before its consolidations,
     * <b>31</b> after. (The figures quoted in #302 and #305 — 33 and "approximately 30" — were
     * counted from annotations rather than from cache keys, which is the reason this test exists
     * and not a discrepancy to reconcile.) Lower it whenever the count drops; raise it only with a
     * reason worth reading.
     */
    private static final int BUDGET = 31;

    /** Where the measured breakdown is written, so a run's numbers survive for a PR body. */
    private static final Path REPORT = Path.of("target", "context-budget.txt");

    @Test
    void the_suite_builds_no_more_distinct_application_contexts_than_the_budget() throws IOException {
        SortedMap<String, String> contextsByKey = distinctContexts();
        writeReport(contextsByKey);

        assertThat(contextsByKey.size())
                .withFailMessage(
                        """
                        The test suite now builds %d distinct Spring application contexts; the budget is %d.

                        Each one is a live context held in Spring's test context cache (bounded to 8 in
                        pom.xml), so crossing the budget costs either heap or repeated context rebuilds.
                        Before raising BUDGET in %s, check whether the new property set could reuse an
                        existing posture, or whether the test needs a full context at all — see issue #305.

                        The property sets and the classes that declare them are in %s.

                        Current sets:
                        %s
                        """,
                        contextsByKey.size(),
                        BUDGET,
                        ContextBudgetTests.class.getSimpleName(),
                        REPORT,
                        contextsByKey.values().stream().collect(Collectors.joining("\n")))
                .isLessThanOrEqualTo(BUDGET);
    }

    /**
     * Every distinct context cache key the suite will ask for, keyed by a stable rendering of the
     * key so the report is diffable between runs.
     */
    private static SortedMap<String, String> distinctContexts() {
        Map<MergedContextConfiguration, StringBuilder> classesByConfiguration = new LinkedHashMap<>();
        ReflectionSupport.findAllClassesInPackage("dev.skillsgateway", ContextBudgetTests::startsAContext, name -> true)
                .forEach(testClass -> classesByConfiguration
                        .computeIfAbsent(
                                BootstrapUtils.resolveTestContextBootstrapper(testClass)
                                        .buildMergedContextConfiguration(),
                                configuration -> new StringBuilder())
                        .append(testClass.getSimpleName())
                        .append(' '));

        SortedMap<String, String> report = new TreeMap<>();
        classesByConfiguration.forEach((configuration, classes) -> report.put(
                render(configuration),
                render(configuration) + "\n      " + classes.toString().trim()));
        return report;
    }

    /**
     * The parts of the cache key a reader can act on: the properties and profiles a test declares.
     * The loader and the customizers are identical across this suite, so printing them would only
     * add noise.
     */
    private static String render(MergedContextConfiguration configuration) {
        String properties = String.join(", ", configuration.getPropertySourceProperties());
        String profiles = String.join(",", configuration.getActiveProfiles());
        return "  [" + (profiles.isEmpty() ? "no profiles" : profiles) + "] "
                + (properties.isEmpty() ? "(base context, no declared properties)" : properties);
    }

    private static boolean startsAContext(Class<?> candidate) {
        return !candidate.isInterface()
                && !java.lang.reflect.Modifier.isAbstract(candidate.getModifiers())
                && MergedAnnotations.from(candidate, SearchStrategy.TYPE_HIERARCHY)
                        .isPresent(SpringBootTest.class);
    }

    private static void writeReport(SortedMap<String, String> contextsByKey) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(
                REPORT,
                "distinct application contexts: " + contextsByKey.size() + "\n\n"
                        + String.join("\n", contextsByKey.values()) + "\n");
    }
}
