package dev.skillsgateway.server.vetting;

import java.util.Map;
import java.util.function.Predicate;

/**
 * A snapshot held in memory, for exercising a vetter without a repository. A {@code null} value
 * is a file over the size cap, as {@link QuarantineSnapshot} reports one.
 */
record InMemorySnapshot(Map<String, byte[]> files) implements SnapshotUnderVetting {

    @Override
    public long snapshotId() {
        return 1;
    }

    @Override
    public String marketplace() {
        return "m";
    }

    @Override
    public String sha() {
        return "1".repeat(40);
    }

    @Override
    public void walk(Predicate<String> wanted, FileVisitor visitor) {
        files.forEach((path, content) -> {
            if (wanted.test(path)) {
                visitor.visit(path, content);
            }
        });
    }
}
