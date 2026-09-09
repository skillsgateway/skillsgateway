package dev.skillsgateway.server.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One rule, enforced on the source rather than on a reviewer's memory: a {@code @Scheduled} method
 * takes a sweep lease.
 *
 * <p>Eight of them existed when the lease was introduced, and the shape of the mistake this guards
 * against is not "somebody removed a lease" — it is "somebody added the ninth sweep". The chart no
 * longer refuses a scaled-out deployment, so a pass added without a lease is a pass that quietly
 * runs on every replica in an estate that was told it was safe to scale.
 *
 * <p>A grep is a blunt instrument. It is also the instrument that fails the build, which a
 * convention is not.
 */
class SweepLeaseDisciplineTests {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void everyScheduledMethodTakesALease() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            List<String> offenders = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(SweepLeaseDisciplineTests::declaresAScheduledMethod)
                    .filter(path -> !read(path).contains("leases.runIfLeader("))
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("a @Scheduled method with no lease runs on every replica, and nothing else"
                            + " stops it now that the chart does not")
                    .isEmpty();
        }
    }

    /**
     * And each takes its own. Two passes sharing a key is the failure the key constants exist to
     * prevent — the six-hourly compaction would hold the hourly evaluation out for six hours — and
     * it is invisible at every level above the string itself.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void noTwoSweepsShareALeaseKey() throws IOException {
        List<String> keys = new ArrayList<>();
        int scheduledMethods = 0;
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            for (Path path : sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(SweepLeaseDisciplineTests::declaresAScheduledMethod)
                    .toList()) {
                String body = read(path);
                scheduledMethods += (int) body.lines()
                        .map(String::trim)
                        .filter(line -> line.startsWith("@Scheduled"))
                        .count();
                body.lines()
                        .map(String::trim)
                        .filter(line -> line.startsWith("public static final String") && line.contains("LEASE"))
                        .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                        .forEach(keys::add);
            }
        }

        assertThat(scheduledMethods).as("the sweeps this rule is about").isEqualTo(8);
        assertThat(keys)
                .as("one key per scheduled pass, and no pass borrowing another's — a shared key means"
                        + " the six-hourly compaction holds the hourly evaluation out for six hours")
                .hasSize(scheduledMethods)
                .doesNotHaveDuplicates();
    }

    /** A source file with a real {@code @Scheduled} method, not one that merely names it in prose. */
    private static boolean declaresAScheduledMethod(Path path) {
        return read(path).lines().map(String::trim).anyMatch(line -> line.startsWith("@Scheduled"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
