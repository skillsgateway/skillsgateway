package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A store that stops answering the conditional write, and only the conditional write.
 *
 * <p>This is how a writer is killed at the one instant that matters: the packs it uploaded are
 * already durable in the bucket, and the manifest that would have named them never lands. Every
 * other operation keeps working, so the repository is left in exactly the state a pod would leave
 * it in if it were terminated between those two steps — not in the state a store-wide outage
 * would leave it in, which would prove nothing about ordering.
 */
public final class CrashingObjectStoreClient implements ObjectStoreClient {

    private final ObjectStoreClient delegate;
    private volatile boolean armed;

    public CrashingObjectStoreClient(ObjectStoreClient delegate) {
        this.delegate = delegate;
    }

    /** From here on, no manifest transition completes. */
    public void kill() {
        armed = true;
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        if (armed && key.endsWith("/manifest")) {
            throw new IOException("the writer died between uploading its packs and naming them");
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
