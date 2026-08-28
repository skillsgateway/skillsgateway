package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A store that slips a <em>disjoint</em> writer in ahead of a conditional write, a fixed number of
 * times, and then gets out of the way.
 *
 * <p>This is the race the design says must not be visible to the caller: an approval writing one
 * reference and a retention pass deleting another both hold valid reference-level preconditions,
 * and both are correct, but only one of them can win the manifest's version race. Reproducing it
 * by hand rather than by luck is the only way to assert that the loser retries and succeeds instead
 * of surfacing as a lock failure JGit will not retry.
 */
public final class RacingObjectStoreClient implements ObjectStoreClient {

    /** A well-formed object id no test writes, so the interposed edit is unmistakably not ours. */
    public static final String COMPETING_OBJECT_ID = "1".repeat(40);

    private final ObjectStoreClient delegate;
    private final String competingRef;
    private final AtomicInteger remaining;

    public RacingObjectStoreClient(ObjectStoreClient delegate, String competingRef, int times) {
        this.delegate = delegate;
        this.competingRef = competingRef;
        this.remaining = new AtomicInteger(times);
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        if (key.endsWith("/manifest") && remaining.getAndDecrement() > 0) {
            Optional<StoredObject> current = delegate.get(key);
            if (current.isPresent()) {
                RepositoryManifest manifest =
                        RepositoryManifest.parse(current.get().body());
                delegate.put(
                        key, manifest.withRef(competingRef, COMPETING_OBJECT_ID).toJson());
            }
        }
        return delegate.putIfMatch(key, body, etag);
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
        return delegate.get(key);
    }

    @Override
    public Optional<StoredObject> getIfChanged(String key, String etag) throws IOException {
        return delegate.getIfChanged(key, etag);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return delegate.exists(key);
    }

    @Override
    public String put(String key, byte[] body) throws IOException {
        return delegate.put(key, body);
    }

    @Override
    public String putFile(String key, Path file) throws IOException {
        return delegate.putFile(key, file);
    }

    @Override
    public Optional<String> putIfAbsent(String key, byte[] body) throws IOException {
        return delegate.putIfAbsent(key, body);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        return delegate.list(prefix);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
    }

    @Override
    public void probe() throws IOException {
        delegate.probe();
    }
}
