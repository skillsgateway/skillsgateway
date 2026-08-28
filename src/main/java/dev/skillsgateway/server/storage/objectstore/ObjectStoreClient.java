package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The narrow slice of an S3-compatible object store this backend needs, and nothing else.
 *
 * <p>The interface exists so the conditional write — the single serialization point of the whole
 * design — is one named operation with one meaning: {@link #putIfMatch} returns empty when, and
 * only when, the store refused the write because the precondition no longer held. Everything
 * above it can then be written as compare-and-swap without ever handling an SDK exception, and a
 * store whose {@code If-Match} is not faithful fails one small surface rather than leaking
 * last-writer-wins semantics through the whole backend.
 *
 * <p>Implementations are thread-safe.
 */
public interface ObjectStoreClient {

    /** An object's bytes together with the version token a conditional write must present. */
    record StoredObject(byte[] body, String etag) {}

    /** Read an object whole. Empty when the key does not exist. */
    Optional<StoredObject> get(String key) throws IOException;

    /**
     * Re-read an object only if it has moved on from {@code etag} — a conditional {@code GET},
     * which is what makes a freshness check {@code O(1)} rather than a download.
     *
     * @return empty when the store answered "not modified"; the current object otherwise
     * @throws java.io.FileNotFoundException the object is gone
     */
    Optional<StoredObject> getIfChanged(String key, String etag) throws IOException;

    /** Stream an object, for a body too large to want in memory. */
    InputStream open(String key) throws IOException;

    /** The object's length in bytes. */
    long size(String key) throws IOException;

    /** Whether the key exists. */
    boolean exists(String key) throws IOException;

    /** Write unconditionally. Used only where no other writer can be racing for the key. */
    String put(String key, byte[] body) throws IOException;

    /** Write a local file unconditionally, streaming it. Used for immutable, content-named packs. */
    String putFile(String key, Path file) throws IOException;

    /**
     * The compare-and-swap. Writes {@code body} only while the stored object is still
     * {@code etag}.
     *
     * @return the new version token, or empty when the store refused the write because the
     *     precondition no longer held
     */
    Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException;

    /**
     * Create exactly once ({@code If-None-Match: *}).
     *
     * @return the new version token, or empty when the object already existed
     */
    Optional<String> putIfAbsent(String key, byte[] body) throws IOException;

    /** Every key under a prefix. */
    List<String> list(String prefix) throws IOException;

    /** Remove a key. Removing a key that is not there is not an error. */
    void delete(String key) throws IOException;

    /**
     * Prove, against the configured bucket, that the store does what the design requires of it:
     * a written object is immediately readable, and a conditional write with a stale precondition
     * is refused rather than accepted. A store that fails this cannot be supported by weakening
     * the model — last-writer-wins on the manifest is exactly the lost update the design exists to
     * prevent — so the only honest response is to refuse to start.
     */
    void probe() throws IOException;
}
