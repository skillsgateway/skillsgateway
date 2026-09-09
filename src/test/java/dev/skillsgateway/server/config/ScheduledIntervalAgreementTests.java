package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.mock.env.MockEnvironment;

/**
 * Every scheduled interval is written down twice, and this is the test that they say the same thing.
 *
 * <p>A {@code @Scheduled} annotation cannot read a bound record — the placeholder is resolved long
 * before the binder runs — so each interval appears as a {@code ${property:default}} literal in the
 * annotation <em>and</em> as a default in {@link SkillsGatewayProperties}' compact constructor. Two
 * places, one value, and nothing forcing them to agree.
 *
 * <p>That duplication used to be harmless because nothing read the record side: the schedule came
 * from the annotation and the record's copy was decoration. It stopped being harmless when the
 * sweeps took leases, because the lease duration is read from the record while the tick that takes
 * it comes from the annotation. A disagreement there does not fail, log, or look wrong — it
 * silently makes the lease shorter or longer than the interval it exists to cover, which is the one
 * assumption the whole coordination design rests on.
 *
 * <p>Deleting one of the two copies would be better than testing them. Neither can go: the
 * annotation's default is what runs when no configuration is present, and the record's is what every
 * reader of the value gets. So the duplication stays and this makes it honest.
 */
class ScheduledIntervalAgreementTests {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** {@code ${skills-gateway.some.property:30s}} — the only placeholder shape the sweeps use. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(skills-gateway\\.[a-z.-]+):([0-9a-z]+)}");

    @Test
    void everyScheduledIntervalMatchesItsBoundDefault() throws IOException {
        Map<String, Duration> boundDefaults = defaults();
        Map<String, String> annotated = annotatedDefaults();

        assertThat(annotated)
                .as("the scheduled sweeps still declare their intervals as placeholders")
                .hasSize(9);

        assertThat(annotated).allSatisfy((path, literal) -> {
            Duration declared = DurationStyle.detectAndParse(literal);
            assertThat(boundDefaults)
                    .as("%s is scheduled from a placeholder but is not a bound property", path)
                    .containsKey(path);
            assertThat(declared)
                    .as(
                            "%s defaults to %s in the @Scheduled annotation and %s in"
                                    + " SkillsGatewayProperties. The annotation drives the tick and the"
                                    + " record drives the lease, so a disagreement makes the lease cover"
                                    + " something other than the gap it is for.",
                            path, literal, boundDefaults.get(path))
                    .isEqualTo(boundDefaults.get(path));
        });
    }

    /** Every {@code ${property:default}} a {@code @Scheduled} annotation in main sources declares. */
    private static Map<String, String> annotatedDefaults() throws IOException {
        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String body = read(path);
                if (!body.contains("@Scheduled")) {
                    return;
                }
                Matcher matcher = PLACEHOLDER.matcher(body);
                while (matcher.find()) {
                    found.put(matcher.group(1), matcher.group(2));
                }
            });
        }
        return found;
    }

    /**
     * Every {@link Duration} the properties record produces when nothing at all is configured —
     * which is exactly the situation the annotation's default is there to cover.
     */
    private static Map<String, Duration> defaults() {
        SkillsGatewayProperties properties =
                Binder.get(new MockEnvironment()).bindOrCreate("skills-gateway", SkillsGatewayProperties.class);
        Map<String, Duration> durations = new LinkedHashMap<>();
        collect(properties, "skills-gateway", durations);
        return durations;
    }

    private static void collect(Object node, String prefix, Map<String, Duration> durations) {
        for (RecordComponent component : node.getClass().getRecordComponents()) {
            String path = prefix + "." + kebab(component.getName());
            Object value = read(node, component);
            if (value instanceof Duration duration) {
                durations.put(path, duration);
            } else if (value != null && value.getClass().isRecord()) {
                collect(value, path, durations);
            }
        }
    }

    private static Object read(Object node, RecordComponent component) {
        try {
            return component.getAccessor().invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read " + component.getName(), e);
        }
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
