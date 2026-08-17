package dev.skillsgateway.server.persistence;

public class SnapshotNotFoundException extends RuntimeException {

    public SnapshotNotFoundException(long id) {
        super("snapshot %d not found".formatted(id));
    }
}
