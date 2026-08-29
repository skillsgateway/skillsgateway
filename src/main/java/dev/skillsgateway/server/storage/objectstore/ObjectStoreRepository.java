package dev.skillsgateway.server.storage.objectstore;

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.RefDatabase;

/** One repository whose objects and references both live in one bucket prefix. */
public final class ObjectStoreRepository extends DfsRepository {

    private final ManifestStore manifests;
    private final ObjectStoreObjDatabase objects;
    private final ObjectStoreRefDatabase refs;

    ObjectStoreRepository(
            DfsRepositoryDescription description,
            ObjectStoreClient store,
            ManifestStore manifests,
            PackCache cache,
            Path scratch,
            String objectPrefix,
            String cacheKeyPrefix,
            int blockSize) {
        super(new Builder().setRepositoryDescription(description));
        this.manifests = manifests;
        this.objects = new ObjectStoreObjDatabase(
                this,
                store,
                manifests,
                cache,
                scratch,
                objectPrefix,
                cacheKeyPrefix,
                blockSize,
                new DfsReaderOptions());
        this.refs = new ObjectStoreRefDatabase(this, manifests);
    }

    @Override
    public ObjectStoreObjDatabase getObjectDatabase() {
        return objects;
    }

    @Override
    public RefDatabase getRefDatabase() {
        return refs;
    }

    /**
     * Re-check the manifest, and drop both caches if it moved.
     *
     * <p>Both caches, because they are two views of one object: a reference that appeared in a
     * transition another replica committed is reachable only through the packs that transition also
     * named, so refreshing the reference map alone would advertise a tip whose objects this replica
     * does not believe exist.
     */
    void freshen() throws IOException {
        if (manifests.freshen()) {
            refs.invalidate();
            objects.invalidate();
        }
    }

    ManifestStore manifests() {
        return manifests;
    }

    /** {@code DfsRepositoryBuilder} is abstract and exists only to hand the description over. */
    private static final class Builder extends DfsRepositoryBuilder<Builder, ObjectStoreRepository> {
        @Override
        public ObjectStoreRepository build() {
            throw new UnsupportedOperationException("ObjectStoreRepository is built by ObjectStoreGitStorage");
        }
    }
}
