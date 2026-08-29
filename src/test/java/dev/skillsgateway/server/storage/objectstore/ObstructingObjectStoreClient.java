package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An object store that can be told to make one reference immovable, which is how the shared storage
 * contract's "a refused transition must be raised, not swallowed" case is provoked on this backend.
 *
 * <p>The filesystem backend is obstructed with a stale lock file, because that is how
 * {@code RefDirectory} refuses a deletion. There is no lock here, so the equivalent has to be built
 * out of what this backend actually does: on every conditional write to an obstructed repository,
 * the manifest is moved on underneath the writer <em>and</em> the obstructed reference is moved with
 * it, then the write is refused. That reproduces both ways this backend can refuse a transition at
 * once — the repository-level compare-and-swap keeps losing, and the reference-level precondition
 * stops holding — which is exactly what the contract wants to be sure is not swallowed.
 */
public final class ObstructingObjectStoreClient implements ObjectStoreClient {

    private final ObjectStoreClient delegate;
    private final Map<String, String> obstructed = new ConcurrentHashMap<>();
    private final AtomicInteger refusals = new AtomicInteger();

    public ObstructingObjectStoreClient(ObjectStoreClient delegate) {
        this.delegate = delegate;
    }

    /** Make every later conditional write to {@code manifestKey} lose, with {@code ref} moving. */
    public void obstruct(String manifestKey, String ref) {
        obstructed.put(manifestKey, ref);
    }

    /** How many conditional writes this decorator has refused. */
    public int refusals() {
        return refusals.get();
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        String ref = obstructed.get(key);
        if (ref == null) {
            return delegate.putIfMatch(key, body, etag);
        }
        refusals.incrementAndGet();
        Optional<StoredObject> current = delegate.get(key);
        if (current.isPresent()) {
            RepositoryManifest manifest = RepositoryManifest.parse(current.get().body());
            delegate.put(key, manifest.withRef(ref, competingObjectId()).toJson());
        }
        return Optional.empty();
    }

    /** A well-formed object id nothing in the test wrote, so the reference has demonstrably moved. */
    private String competingObjectId() {
        String digits = Integer.toHexString(refusals.get() + 0x1000_0000);
        return (digits + "0".repeat(40)).substring(0, 40);
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
