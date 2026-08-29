package dev.skillsgateway.server.storage.objectstore;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A store that answers the startup probe and refuses to do anything else.
 *
 * <p>It exists so backend <em>selection</em> can be tested without a container, and every method
 * beyond the probe throws on purpose. The whole correctness argument of this backend reduces to a
 * conditional write behaving faithfully, so a double that could be used for a concurrency test
 * would be a double that could certify a broken implementation green. Making that impossible is
 * worth more than the convenience it costs.
 */
final class NonWritingObjectStoreClient implements ObjectStoreClient {

    @Override
    public void probe() {
        // Selection tests are about which backend was chosen, not about the store behind it.
    }

    @Override
    public Optional<StoredObject> get(String key) {
        throw refuse();
    }

    @Override
    public Optional<StoredObject> getIfChanged(String key, String etag) {
        throw refuse();
    }

    @Override
    public InputStream open(String key) {
        throw refuse();
    }

    @Override
    public long size(String key) {
        throw refuse();
    }

    @Override
    public boolean exists(String key) {
        throw refuse();
    }

    @Override
    public String put(String key, byte[] body) {
        throw refuse();
    }

    @Override
    public String putFile(String key, Path file) {
        throw refuse();
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) {
        throw refuse();
    }

    @Override
    public Optional<String> putIfAbsent(String key, byte[] body) {
        throw refuse();
    }

    @Override
    public List<String> list(String prefix) {
        throw refuse();
    }

    @Override
    public void delete(String key) {
        throw refuse();
    }

    private static UnsupportedOperationException refuse() {
        return new UnsupportedOperationException(
                "this double answers the startup probe only; anything that stores or reads state must run against a"
                        + " real store, or a broken backend could pass its own concurrency tests");
    }
}
