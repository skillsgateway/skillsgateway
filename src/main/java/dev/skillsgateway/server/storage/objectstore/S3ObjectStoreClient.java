package dev.skillsgateway.server.storage.objectstore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link ObjectStoreClient} over the AWS SDK's synchronous S3 client. */
public final class S3ObjectStoreClient implements ObjectStoreClient, AutoCloseable {

    /** What an S3-compatible store answers when a conditional write's precondition failed. */
    private static final int PRECONDITION_FAILED = 412;

    /** What a conditional GET answers when the caller's copy is still current. */
    private static final int NOT_MODIFIED = 304;

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStoreClient(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
        try {
            var response = s3.getObjectAsBytes(r -> r.bucket(bucket).key(key));
            return Optional.of(whole(key, response));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw failure("read", key, e);
        }
    }

    @Override
    public Optional<StoredObject> getIfChanged(String key, String etag) throws IOException {
        try {
            var response = s3.getObjectAsBytes(r -> r.bucket(bucket).key(key).ifNoneMatch(etag));
            return Optional.of(whole(key, response));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(bucket + "/" + key);
        } catch (S3Exception e) {
            if (e.statusCode() == NOT_MODIFIED) {
                return Optional.empty();
            }
            throw failure("conditionally read", key, e);
        } catch (SdkException e) {
            throw failure("conditionally read", key, e);
        }
    }

    /**
     * The object, checked against the length the store said it was sending.
     *
     * <p>A body shorter than its own {@code Content-Length} is the store failing, not the caller
     * misreading, and it is worth one comparison to say so. Without this a short read surfaces
     * wherever the bytes are finally interpreted — as a JSON parse error on the manifest, which
     * reads like a corrupt repository rather than like a store that did not finish a response.
     * The manifest is the only state this backend has; a half-read one must be an error at the
     * boundary it came from and never a value anything acts on.
     */
    private StoredObject whole(String key, ResponseBytes<GetObjectResponse> response) throws IOException {
        byte[] body = response.asByteArray();
        Long declared = response.response().contentLength();
        if (declared != null && declared != body.length) {
            throw new IOException("the object store returned %d of the %d bytes it declared for %s/%s"
                    .formatted(body.length, declared, bucket, key));
        }
        return new StoredObject(body, response.response().eTag());
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(r -> r.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(bucket + "/" + key);
        } catch (SdkException e) {
            throw failure("open", key, e);
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return s3.headObject(r -> r.bucket(bucket).key(key)).contentLength();
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(bucket + "/" + key);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new FileNotFoundException(bucket + "/" + key);
            }
            throw failure("stat", key, e);
        } catch (SdkException e) {
            throw failure("stat", key, e);
        }
    }

    @Override
    public boolean exists(String key) throws IOException {
        try {
            s3.headObject(r -> r.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw failure("stat", key, e);
        } catch (SdkException e) {
            throw failure("stat", key, e);
        }
    }

    @Override
    public String put(String key, byte[] body) throws IOException {
        try {
            return s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromBytes(body))
                    .eTag();
        } catch (SdkException e) {
            throw failure("write", key, e);
        }
    }

    @Override
    public String putFile(String key, Path file) throws IOException {
        try {
            return s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromFile(file))
                    .eTag();
        } catch (SdkException e) {
            throw failure("write", key, e);
        }
    }

    @Override
    public Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
        try {
            return Optional.of(s3.putObject(r -> r.bucket(bucket).key(key).ifMatch(etag), RequestBody.fromBytes(body))
                    .eTag());
        } catch (S3Exception e) {
            if (e.statusCode() == PRECONDITION_FAILED) {
                return Optional.empty();
            }
            throw failure("conditionally write", key, e);
        } catch (SdkException e) {
            throw failure("conditionally write", key, e);
        }
    }

    @Override
    public Optional<String> putIfAbsent(String key, byte[] body) throws IOException {
        try {
            return Optional.of(
                    s3.putObject(r -> r.bucket(bucket).key(key).ifNoneMatch("*"), RequestBody.fromBytes(body))
                            .eTag());
        } catch (S3Exception e) {
            if (e.statusCode() == PRECONDITION_FAILED) {
                return Optional.empty();
            }
            throw failure("create", key, e);
        } catch (SdkException e) {
            throw failure("create", key, e);
        }
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        try {
            List<String> keys = new ArrayList<>();
            s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix))
                    .contents()
                    .forEach(o -> keys.add(o.key()));
            return keys;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (SdkException e) {
            throw failure("list", prefix, e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw failure("delete", key, e);
        }
    }

    @Override
    public void probe() throws IOException {
        String key = "_probe/" + UUID.randomUUID();
        byte[] first = "probe".getBytes(StandardCharsets.UTF_8);
        try {
            String etag = put(key, first);
            StoredObject read = get(key).orElseThrow(() ->
                    new IOException("object store %s has no read-after-write: %s was written and then not found"
                            .formatted(bucket, key)));
            if (!java.util.Arrays.equals(first, read.body())) {
                throw new IOException("object store %s returned different bytes than were written".formatted(bucket));
            }
            String moved = putIfMatch(key, "moved".getBytes(StandardCharsets.UTF_8), etag)
                    .orElseThrow(() -> new IOException(
                            "object store %s refused a conditional write whose precondition held".formatted(bucket)));
            if (putIfMatch(key, "lost update".getBytes(StandardCharsets.UTF_8), etag)
                    .isPresent()) {
                throw new IOException(
                        ("object store %s accepted a conditional write with a stale precondition, so it cannot "
                                        + "serialize reference transitions; this backend has no degraded mode")
                                .formatted(bucket));
            }
            if (putIfAbsent(key, "already there".getBytes(StandardCharsets.UTF_8))
                    .isPresent()) {
                throw new IOException(
                        "object store %s accepted a create of an object that already exists".formatted(bucket));
            }
            if (moved.equals(etag)) {
                throw new IOException(
                        "object store %s did not change the version token after a write".formatted(bucket));
            }
        } finally {
            try {
                delete(key);
            } catch (IOException ignored) {
                // A leftover probe object is harmless; failing the probe for it would not be.
            }
        }
    }

    /** Releases the connection pool. Harmless when the client is shared and outlives this wrapper. */
    @Override
    public void close() {
        s3.close();
    }

    private IOException failure(String what, String key, SdkException cause) {
        return new IOException("could not %s %s/%s".formatted(what, bucket, key), cause);
    }
}
