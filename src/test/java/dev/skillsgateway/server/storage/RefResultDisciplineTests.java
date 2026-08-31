package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One rule, enforced on the source rather than on a reviewer's memory: reference transitions go
 * through {@link RefTransitions}, which checks what they returned.
 *
 * <p>Five call sites discarded a {@code RefUpdate.Result} before this change — publication, the
 * catalog rebuild and its prune, the ingestion pin, the retention pin delete, and the HEAD link at
 * repository creation — while two others checked theirs. The bug was not any one of them; it was
 * that checking was a habit rather than a rule, so each new caller got a fresh chance to forget. A
 * grep is a blunt instrument, but it is the instrument that fails the build when the habit lapses.
 *
 * <p>Adding a legitimate exception means adding it here deliberately, with a reason.
 */
class RefResultDisciplineTests {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** The one file allowed to touch {@code updateRef}: it is what performs the check. */
    private static final String CHECKER = "RefTransitions.java";

    @Test
    @SVCs({"SVC_GW_0133"})
    void onlyTheCheckedHelperPerformsReferenceTransitions() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            List<String> offenders = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(CHECKER))
                    .flatMap(path -> read(path)
                            .lines()
                            .filter(line -> line.contains("updateRef("))
                            .filter(line -> !line.trim().startsWith("*"))
                            .filter(line -> !line.trim().startsWith("//"))
                            .map(line -> path + ": " + line.trim()))
                    .toList();

            assertThat(offenders)
                    .as("a reference transition outside RefTransitions is a discarded result waiting"
                            + " for the interleaving that makes it matter")
                    .isEmpty();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
