package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free verification that a chain run reads a snapshot once (GW_0162), driven against a
 * real JGit repository.
 *
 * <p>"Never opened" is asserted the only way that cannot be faked: the blob is removed from the
 * object database before the snapshot is constructed. An implementation that materializes content
 * the selection did not ask for — the behaviour this change removes — fails to find it and throws.
 * "Inflated once" is asserted by array identity, which no re-read can produce.
 */
class QuarantineSnapshotTests {

    private static final long MAX_FILE_BYTES = 64;
    private static final long CACHE_BYTES = 1024;

    private static final String LICENSE = "MIT license text";
    private static final String README = "# readme";
    private static final String UNSELECTED = "nobody asks for this";
    private static final String OVERSIZE = "x".repeat(200);

    @TempDir
    Path work;

    private String sha;

    @BeforeEach
    void commitSnapshot() throws Exception {
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            write("LICENSE", LICENSE);
            write("docs/readme.md", README);
            write("unselected.bin", UNSELECTED);
            write("oversize.md", OVERSIZE);
            git.add().addFilepattern(".").call();
            sha = commit(git, "snapshot");
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void aBlobOutsideTheSelectionIsNeverOpened() throws Exception {
        removeFromObjectDatabase("unselected.bin");

        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, CACHE_BYTES)) {
            Map<String, byte[]> visited = walk(snapshot, path -> path.endsWith(".md"));

            assertThat(visited).containsOnlyKeys("docs/readme.md", "oversize.md");
            assertThat(visited.get("docs/readme.md"))
                    .asString(StandardCharsets.UTF_8)
                    .isEqualTo(README);
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void aBlobInsideTheSelectionIsStillRequired() throws Exception {
        removeFromObjectDatabase("unselected.bin");

        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, CACHE_BYTES)) {
            // The counterpart of the test above: the missing blob is genuinely unreachable, so the
            // pass above is the selection working and not the object still being readable.
            assertThatThrownBy(() -> walk(snapshot, path -> true)).isInstanceOf(IOException.class);
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void aBlobTwoConnectorsSelectIsInflatedOnce() throws Exception {
        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, CACHE_BYTES)) {
            byte[] first = walk(snapshot, "LICENSE"::equals).get("LICENSE");
            byte[] second = walk(snapshot, path -> true).get("LICENSE");

            assertThat(first).asString(StandardCharsets.UTF_8).isEqualTo(LICENSE);
            assertThat(second).isSameAs(first);
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void anOversizeFileIsStillVisitedAsUnread() throws Exception {
        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, CACHE_BYTES)) {
            Map<String, byte[]> visited = walk(snapshot, path -> true);

            assertThat(visited).containsKey("oversize.md");
            assertThat(visited.get("oversize.md")).isNull();
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void contentBeyondTheRetentionBoundIsStillServedInFull() throws Exception {
        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, 0)) {
            byte[] first = walk(snapshot, path -> true).get("LICENSE");
            byte[] second = walk(snapshot, path -> true).get("LICENSE");

            assertThat(first).asString(StandardCharsets.UTF_8).isEqualTo(LICENSE);
            assertThat(second).isEqualTo(first).isNotSameAs(first);
        }
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void identicalFilesShareOneCacheEntry() throws Exception {
        write("copy/LICENSE", LICENSE);
        try (Git git = Git.open(work.toFile())) {
            git.add().addFilepattern(".").call();
            sha = commit(git, "copy");
        }

        try (QuarantineSnapshot snapshot = open(MAX_FILE_BYTES, CACHE_BYTES)) {
            Map<String, byte[]> visited = walk(snapshot, path -> path.endsWith("LICENSE"));

            assertThat(visited).containsOnlyKeys("LICENSE", "copy/LICENSE");
            assertThat(visited.get("copy/LICENSE")).isSameAs(visited.get("LICENSE"));
        }
    }

    /** An identity is supplied explicitly: the test must not depend on the machine's git config. */
    private static String commit(Git git, String message) throws Exception {
        return git.commit()
                .setMessage(message)
                .setSign(false)
                .setAuthor("test", "test@example.invalid")
                .setCommitter("test", "test@example.invalid")
                .call()
                .name();
    }

    private QuarantineSnapshot open(long maxFileBytes, long contentCacheBytes) throws IOException {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(work.resolve(".git").toFile())
                .build();
        return new QuarantineSnapshot(1, "m", sha, maxFileBytes, contentCacheBytes, repository);
    }

    private static Map<String, byte[]> walk(QuarantineSnapshot snapshot, java.util.function.Predicate<String> wanted)
            throws IOException {
        Map<String, byte[]> visited = new LinkedHashMap<>();
        snapshot.walk(wanted, visited::put);
        return visited;
    }

    /** Removes a committed file's blob from the object database, leaving the tree pointing at it. */
    private void removeFromObjectDatabase(String path) throws IOException {
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(work.resolve(".git").toFile())
                .build()) {
            ObjectId blob = repository.resolve(sha + ":" + path);
            assertThat(blob).isNotNull();
            String name = blob.name();
            Path loose =
                    work.resolve(".git/objects").resolve(name.substring(0, 2)).resolve(name.substring(2));
            assertThat(Files.deleteIfExists(loose)).isTrue();
        }
    }

    private void write(String path, String content) throws IOException {
        Path file = work.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
