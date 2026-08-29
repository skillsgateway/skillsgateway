package dev.skillsgateway.server.observability;

import dev.skillsgateway.server.storage.objectstore.ObjectStoreClient;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Times every object-store request, and nothing else (GW_0116).
 *
 * <p>This backend's latency is not its own: it is the store's, and on the first deployment target
 * it is a network away across a NAT gateway. When an approval is slow, or a fetch is, the question
 * is always whether the gateway was waiting on the bucket — and there is no answer to that except
 * a timer around the requests. It sits here as a decorator rather than inside the client so that
 * the client stays the narrow, testable slice of S3 it was written to be, and so a deployment with
 * no metrics registry runs the undecorated one.
 *
 * <p>Both tags are closed vocabularies: {@code operation} is one per method on the interface, and
 * {@code outcome} is success or error. No key, bucket or repository appears — an object key is
 * unbounded cardinality, and the one thing certain about an unbounded tag is that it is discovered
 * when the metrics backend falls over.
 */
public final class MeteredObjectStoreClient implements ObjectStoreClient, AutoCloseable {

    /** Timer around one object-store request, tagged {@code operation} and {@code outcome}. */
    public static final String REQUESTS = "skills_gateway.storage.requests";

    private final ObjectStoreClient delegate;
    private final MeterRegistry meters;

    public MeteredObjectStoreClient(ObjectStoreClient delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
        return time("get", () -> delegate.get(key));
    }

    @Override
    public Optional<StoredObject> getIfChanged(String key, String etag) throws IOException {
        return time("conditional-get", () -> delegate.getIfChanged(key, etag));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return time("open", () -> delegate.open(key));
    }

    @Override
    public long size(String key) throws IOException {
        return time("stat", () -> delegate.size(key));
    }

    @Override
    public boolean exists(String key) throws IOException {
        return time("stat", () -> delegate.exists(key));
    }

    @Override
    public String put(String key, byte[] body) throws IOException {
        return time("put", () -> delegate.put(key, body));
    }

    @Override
    public String putFile(String key, Path file) throws IOException {
        return time("put", () -> delegate.putFile(key, file));
    }

    @Override
    @Requirements({"GW_0116"})
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        return time("conditional-put", () -> delegate.putIfMatch(key, body, etag));
    }

    @Override
    public Optional<String> putIfAbsent(String key, byte[] body) throws IOException {
        return time("create", () -> delegate.putIfAbsent(key, body));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        return time("list", () -> delegate.list(prefix));
    }

    @Override
    public void delete(String key) throws IOException {
        time("delete", () -> {
            delegate.delete(key);
            return null;
        });
    }

    @Override
    public void probe() throws IOException {
        time("probe", () -> {
            delegate.probe();
            return null;
        });
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /**
     * The one wrapping shape: time it, tag the outcome, and hand the result or the failure back
     * exactly as it came. A refused conditional write is a <em>success</em> here — the store
     * answered, and it answered correctly; counting it as an error would make normal contention
     * look like a broken bucket. {@code ObjectStoreMetrics} counts those refusals separately,
     * which is where they mean something.
     */
    private <T> T time(String operation, Call<T> call) throws IOException {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return call.get();
        } catch (IOException | RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(meters.timer(REQUESTS, "operation", operation, "outcome", outcome));
        }
    }

    @FunctionalInterface
    private interface Call<T> {
        T get() throws IOException;
    }
}
