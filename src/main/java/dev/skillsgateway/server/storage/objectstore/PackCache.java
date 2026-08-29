package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded local copy of packs the bucket already holds. Nothing in it is authoritative.
 *
 * <p>Pack objects are immutable and named independently of their content's mutability — a pack is
 * written once and never rewritten — so a cached pack can never be stale and needs no invalidation
 * pass. Only the manifest is re-read. That is what makes deleting this directory safe at any
 * moment, including while the gateway is running: the next open re-fetches.
 *
 * <p>A whole object is fetched on first open rather than read in ranges. It costs more on a cold
 * replica and less on every subsequent read, and it narrows a real failure window: a pack deleted
 * by compaction while a replica is part-way through streaming it would otherwise fail in the
 * middle of a fetch. The grace period on pack deletion is the other half of that; this is the
 * half that shortens the exposure.
 */
public final class PackCache {

    private final Path root;
    private final long maxBytes;
    private final ObjectStoreStatistics statistics;

    /** Files an open channel is reading, which eviction must not delete out from under it. */
    private final Set<Path> inUse = ConcurrentHashMap.newKeySet();

    PackCache(Path root, long maxBytes, ObjectStoreStatistics statistics) throws IOException {
        this.root = root;
        this.maxBytes = maxBytes;
        this.statistics = statistics;
        Files.createDirectories(root);
    }

    /**
     * The local path of a stored object, fetching it if this replica has not seen it before.
     *
     * @param objectKey the object's key in the bucket
     * @param cacheName a name unique to that key within the cache
     */
    Path localCopy(ObjectStoreClient store, String objectKey, String cacheName) throws IOException {
        Path cached = root.resolve(cacheName);
        if (Files.isRegularFile(cached)) {
            statistics.packCacheHit();
            touch(cached);
            return cached;
        }
        Files.createDirectories(root);
        Path staging = root.resolve(cacheName + "." + UUID.randomUUID() + ".tmp");
        try (InputStream in = store.open(objectKey)) {
            Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staging, cached, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                // Another thread fetched the same immutable object first; either copy will do.
                Files.deleteIfExists(staging);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        statistics.packDownloaded();
        evictDownToBudget();
        return cached;
    }

    /** Adopt a file this replica just wrote, so the pack it uploaded is not fetched straight back. */
    void adopt(Path file, String cacheName) throws IOException {
        Path cached = root.resolve(cacheName);
        try {
            Files.move(file, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(file);
            return;
        }
        evictDownToBudget();
    }

    /** Mark a cached file as being read, so eviction leaves it alone. */
    void pin(Path file) {
        inUse.add(file);
    }

    /** Release a pin taken by {@link #pin(Path)}. */
    void unpin(Path file) {
        inUse.remove(file);
    }

    private void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // Access time is an eviction hint, not state; failing to record it is not a fault.
        }
    }

    private synchronized void evictDownToBudget() throws IOException {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (var stream = Files.list(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                files.add(file);
                total += Files.size(file);
            }
        }
        if (total <= maxBytes) {
            return;
        }
        files.sort(Comparator.comparing(PackCache::lastModified));
        for (Path file : files) {
            if (total <= maxBytes) {
                return;
            }
            if (inUse.contains(file)) {
                continue;
            }
            long size = Files.size(file);
            if (Files.deleteIfExists(file)) {
                total -= size;
            }
        }
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
