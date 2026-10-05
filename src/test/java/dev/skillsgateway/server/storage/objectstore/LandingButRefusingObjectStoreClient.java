package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A store whose conditional manifest write lands and is then reported as refused, a fixed number of
 * times once armed.
 *
 * <p>This is what a lost response followed by an SDK retry looks like from the client: the first
 * {@code PUT} replaced the manifest, the retry presents the old ETag and gets 412. The writer must
 * recognise its own write rather than re-evaluate against it and report a conflict.
 */
public final class LandingButRefusingObjectStoreClient implements ObjectStoreClient {

    private final ObjectStoreClient delegate;
    private final AtomicInteger remaining = new AtomicInteger();

    public LandingButRefusingObjectStoreClient(ObjectStoreClient delegate) {
        this.delegate = delegate;
    }

    /** From now on, the next {@code times} manifest writes land and are reported refused. */
    public void arm(int times) {
        remaining.set(times);
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        Optional<String> written = delegate.putIfMatch(key, body, etag);
        if (written.isPresent() && key.endsWith("/manifest") && remaining.getAndDecrement() > 0) {
            return Optional.empty();
        }
        return written;
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
    public void probe(String keyPrefix) throws IOException {
        delegate.probe(keyPrefix);
    }
}
