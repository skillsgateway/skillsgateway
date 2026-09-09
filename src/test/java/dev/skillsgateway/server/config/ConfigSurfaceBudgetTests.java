package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The configuration surface's budget, asserted rather than assumed.
 *
 * <p>Every leaf of {@link SkillsGatewayProperties} is a promise: a name an operator may set, a
 * default that has to stay defensible, a row in the reference, and a combination the suite is
 * supposed to cover. The surface reached its current size without anybody deciding it should,
 * because adding a component to a nested record costs one line and nothing counts the result.
 *
 * <p>This is that counter, and it is deliberately the crudest possible one — a walk over record
 * components, no Spring context, no binder. A knob that is genuinely needed still gets added; what
 * changes is that adding it is now a visible act with a number attached, in the style of
 * {@code ContextBudgetTests}.
 *
 * <p><b>The budget is a ratchet, not a target.</b> Lower it whenever the count drops. Raising it
 * means writing down why a deployment cannot express what it needs with the leaves that already
 * exist — which is the question nobody was asked the last twenty times.
 */
class ConfigSurfaceBudgetTests {

    /**
     * The number of settable leaves the gateway is allowed to offer.
     *
     * <p><b>104, measured.</b> The assessment that asked for this test counted 127 by hand; that
     * figure does not reproduce, and the discrepancy is the argument for the test rather than a
     * detail to reconcile — a surface nobody can count twice the same way is a surface nobody is
     * deciding the size of. All 105 are documented in the configuration reference, checked leaf by
     * leaf against its tables when this was written; it was 105 then, and removing
     * {@code storage.object-store.cache.ref-freshness} is what took it to 104.
     *
     * <p>The cuts the assessment proposed are separate changes, and each one lowers this number.
     */
    private static final int BUDGET = 104;

    /** Where the measured breakdown is written, so a run's numbers survive for a PR body. */
    private static final Path REPORT = Path.of("target", "config-surface.txt");

    @Test
    void theGatewayOffersNoMoreConfigurationLeavesThanTheBudget() throws IOException {
        List<String> leaves = leaves(SkillsGatewayProperties.class, "skills-gateway");
        writeReport(leaves);

        assertThat(leaves.size())
                .withFailMessage("""
                        The gateway now offers %d configuration leaves; the budget is %d.

                        Every leaf is a name an operator may set and a default that has to stay
                        defensible. Before raising BUDGET in %s, answer the question the surface grew
                        without ever being asked: can a deployment express what it needs with a leaf
                        that already exists? A knob added "in case somebody wants it" is a knob
                        nobody sets, documents or tests, and the reference has to carry it anyway.

                        The full list is in %s.
                        """, leaves.size(), BUDGET, ConfigSurfaceBudgetTests.class.getSimpleName(), REPORT)
                .isLessThanOrEqualTo(BUDGET);
    }

    /**
     * Every settable name under {@code prefix}, in relaxed-binding form. A component whose type is
     * one of the properties records is a branch and contributes nothing itself; everything else is
     * a leaf, including a {@code Map} or {@code List} whose contents the binder will fill — those
     * are one name an operator sets, not an open-ended count.
     */
    private static List<String> leaves(Class<?> record, String prefix) {
        List<String> found = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            String path = prefix + "." + kebab(component.getName());
            if (isPropertiesRecord(component.getType())) {
                found.addAll(leaves(component.getType(), path));
            } else {
                found.add(path);
            }
        }
        return found;
    }

    /** A nested record of the properties tree, as opposed to a value type that happens to be one. */
    private static boolean isPropertiesRecord(Class<?> type) {
        return type.isRecord() && type.getName().startsWith(SkillsGatewayProperties.class.getName());
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static void writeReport(List<String> leaves) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "configuration leaves: " + leaves.size() + "\n\n" + String.join("\n", leaves) + "\n");
    }
}
