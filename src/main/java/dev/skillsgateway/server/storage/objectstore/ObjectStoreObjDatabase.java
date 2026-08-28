package dev.skillsgateway.server.storage.objectstore;

import dev.skillsgateway.server.storage.objectstore.RepositoryManifest.PackEntry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;

/**
 * The pack half of the repository: immutable objects in the bucket, a list of them in the manifest.
 *
 * <p>A pack is uploaded whole, under a name nothing else will ever use, and is invisible until the
 * manifest names it. So the durability order the design requires — objects durable before any
 * reference reaches them — is not a rule to remember; it is the only order this code can express.
 * A pack whose commit loses the compare-and-swap is simply an unreferenced object, which
 * compaction collects.
 */
final class ObjectStoreObjDatabase extends DfsObjDatabase {

    private final ObjectStoreClient store;
    private final ManifestStore manifests;
    private final PackCache cache;
    private final Path scratch;
    private final String objectPrefix;
    private final String cacheKeyPrefix;
    private final int blockSize;

    ObjectStoreObjDatabase(
            ObjectStoreRepository repository,
            ObjectStoreClient store,
            ManifestStore manifests,
            PackCache cache,
            Path scratch,
            String objectPrefix,
            String cacheKeyPrefix,
            int blockSize,
            DfsReaderOptions options) {
        super(repository, options);
        this.store = store;
        this.manifests = manifests;
        this.cache = cache;
        this.scratch = scratch;
        this.objectPrefix = objectPrefix;
        this.cacheKeyPrefix = cacheKeyPrefix;
        this.blockSize = blockSize;
    }

    @Override
    protected List<DfsPackDescription> listPacks() throws IOException {
        List<DfsPackDescription> packs = new ArrayList<>();
        for (PackEntry entry : manifests.current().manifest().packs()) {
            packs.add(toDescription(entry));
        }
        return packs;
    }

    @Override
    protected DfsPackDescription newPack(PackSource source) {
        // Content-independent and collision-free by construction: two writers racing to insert the
        // same objects produce two packs, and the loser's is garbage rather than a corrupted merge.
        String name = "pack-%s-%s".formatted(UUID.randomUUID(), source.name().toLowerCase(Locale.ROOT));
        return new DfsPackDescription(getRepository().getDescription(), name, source);
    }

    @Override
    protected void commitPackImpl(Collection<DfsPackDescription> desc, Collection<DfsPackDescription> replaces)
            throws IOException {
        List<PackEntry> added =
                desc.stream().map(ObjectStoreObjDatabase::toEntry).toList();
        List<String> removed = replaces == null
                ? List.of()
                : replaces.stream().map(DfsPackDescription::getPackName).toList();
        String description =
                "commit of %d pack(s)%s".formatted(added.size(), removed.isEmpty() ? "" : " replacing " + removed);
        manifests.transact(
                description,
                current ->
                        new ManifestStore.Step<>(current.withPacks(added, removed, System.currentTimeMillis()), null));
        clearCache();
    }

    @Override
    protected void rollbackPack(Collection<DfsPackDescription> desc) {
        for (DfsPackDescription pack : desc) {
            for (PackExt ext : PackExt.values()) {
                if (pack.hasFileExt(ext)) {
                    try {
                        store.delete(objectPrefix + pack.getFileName(ext));
                    } catch (IOException e) {
                        // A rollback runs only when something has already failed, and an
                        // unreferenced object is collectable garbage rather than corruption.
                    }
                }
            }
        }
    }

    @Override
    protected ReadableChannel openFile(DfsPackDescription desc, PackExt ext) throws IOException {
        String fileName = desc.getFileName(ext);
        Path local = cache.localCopy(store, objectPrefix + fileName, cacheKeyPrefix + fileName);
        if (!Files.isRegularFile(local)) {
            throw new FileNotFoundException(fileName);
        }
        return new CachedFileChannel(cache, local, blockSize);
    }

    @Override
    protected DfsOutputStream writeFile(DfsPackDescription desc, PackExt ext) throws IOException {
        Files.createDirectories(scratch);
        Path staging = scratch.resolve(desc.getFileName(ext) + "." + UUID.randomUUID() + ".tmp");
        return new UploadOnClose(
                store, cache, staging, objectPrefix + desc.getFileName(ext), cacheKeyPrefix + desc.getFileName(ext));
    }

    @Override
    public Set<ObjectId> getShallowCommits() {
        // The gateway ingests and serves whole histories; nothing here creates a shallow clone.
        return Collections.emptySet();
    }

    @Override
    public void setShallowCommits(Set<ObjectId> shallowCommits) {
        if (!shallowCommits.isEmpty()) {
            throw new UnsupportedOperationException("shallow repositories are not served by this gateway");
        }
    }

    @Override
    public long getApproximateObjectCount() {
        try {
            long count = 0;
            for (PackEntry entry : manifests.current().manifest().packs()) {
                count += entry.objectCount();
            }
            return count;
        } catch (IOException e) {
            return -1;
        }
    }

    ObjectStoreClient client() {
        return store;
    }

    String objectPrefix() {
        return objectPrefix;
    }

    /** Drop the cached pack list; the manifest under it has already moved. */
    void invalidate() {
        clearCache();
    }

    DfsPackDescription toDescription(PackEntry entry) {
        DfsPackDescription desc = new DfsPackDescription(
                getRepository().getDescription(), entry.name(), PackSource.valueOf(entry.source()));
        desc.setObjectCount(entry.objectCount());
        desc.setDeltaCount(entry.deltaCount());
        desc.setIndexVersion(entry.indexVersion());
        desc.setMinUpdateIndex(entry.minUpdateIndex());
        desc.setMaxUpdateIndex(entry.maxUpdateIndex());
        for (PackExt ext : PackExt.values()) {
            Long size = entry.sizes().get(ext.getExtension());
            if (size != null) {
                desc.addFileExt(ext);
                desc.setFileSize(ext, size);
            }
            Integer blockSize = entry.blockSizes().get(ext.getExtension());
            if (blockSize != null) {
                desc.setBlockSize(ext, blockSize);
            }
        }
        return desc;
    }

    private static PackEntry toEntry(DfsPackDescription desc) {
        Map<String, Long> sizes = new HashMap<>();
        Map<String, Integer> blockSizes = new HashMap<>();
        for (PackExt ext : PackExt.values()) {
            if (desc.hasFileExt(ext)) {
                sizes.put(ext.getExtension(), desc.getFileSize(ext));
                int blockSize = desc.getBlockSize(ext);
                if (blockSize > 0) {
                    blockSizes.put(ext.getExtension(), blockSize);
                }
            }
        }
        return new PackEntry(
                desc.getPackName(),
                desc.getPackSource().name(),
                desc.getObjectCount(),
                desc.getDeltaCount(),
                desc.getIndexVersion(),
                desc.getMinUpdateIndex(),
                desc.getMaxUpdateIndex(),
                sizes,
                blockSizes);
    }

    /** A read over a locally cached copy of an immutable object. */
    private static final class CachedFileChannel implements ReadableChannel {

        private final PackCache cache;
        private final Path file;
        private final FileChannel channel;
        private final int blockSize;

        CachedFileChannel(PackCache cache, Path file, int blockSize) throws IOException {
            this.cache = cache;
            this.file = file;
            this.blockSize = blockSize;
            cache.pin(file);
            try {
                this.channel = FileChannel.open(file, StandardOpenOption.READ);
            } catch (IOException e) {
                cache.unpin(file);
                throw e;
            }
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return channel.read(dst);
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                cache.unpin(file);
            }
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public long position() throws IOException {
            return channel.position();
        }

        @Override
        public void position(long newPosition) throws IOException {
            channel.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int blockSize() {
            return blockSize;
        }

        @Override
        public void setReadAheadBytes(int bufferSize) {
            // The whole object is already local; there is nothing to read ahead of.
        }
    }

    /**
     * Buffers a pack to local disk and uploads it once, on close. JGit reads back from this stream
     * while it is still being written, which is why the buffer is a real file rather than a pipe.
     */
    private static final class UploadOnClose extends DfsOutputStream {

        private final ObjectStoreClient store;
        private final PackCache cache;
        private final Path staging;
        private final String key;
        private final String cacheName;
        private final RandomAccessFile file;
        private boolean uploaded;

        UploadOnClose(ObjectStoreClient store, PackCache cache, Path staging, String key, String cacheName)
                throws IOException {
            this.store = store;
            this.cache = cache;
            this.staging = staging;
            this.key = key;
            this.cacheName = cacheName;
            this.file = new RandomAccessFile(staging.toFile(), "rw");
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            file.seek(file.length());
            file.write(buf, off, len);
        }

        @Override
        public int read(long position, ByteBuffer buf) throws IOException {
            long length = file.length();
            if (position >= length) {
                return 0;
            }
            byte[] chunk = new byte[(int) Math.min(buf.remaining(), length - position)];
            file.seek(position);
            file.readFully(chunk);
            buf.put(chunk);
            return chunk.length;
        }

        @Override
        public void close() throws IOException {
            if (uploaded) {
                return;
            }
            uploaded = true;
            try {
                file.close();
                store.putFile(key, staging);
                cache.adopt(staging, cacheName);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
    }
}
