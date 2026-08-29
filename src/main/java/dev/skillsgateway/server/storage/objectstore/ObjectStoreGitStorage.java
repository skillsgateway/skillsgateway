package dev.skillsgateway.server.storage.objectstore;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

/**
 * {@link GitStorage} over an S3-compatible bucket: JGit DFS for the objects, one conditionally
 * written manifest per repository for the references.
 *
 * <p>The three repository roles are three key prefixes in one bucket, which keeps the property the
 * filesystem backend gets from three directories — quarantine is never served, the publisher's
 * origin is a third repository and not a view of quarantine — without needing three buckets.
 *
 * <p>{@link #unpublish} is where this backend is not merely equivalent to the filesystem one but
 * strictly better defined. On a filesystem it is two reference deletions with a read between them,
 * and "is the served tip still this snapshot" and "remove it" are not one operation; that has been
 * safe only because there is exactly one writer. Here the whole contract — the pinned reference
 * removed unconditionally, the served tip removed only while it is still this snapshot, and the
 * answer to which of those stopped the marketplace serving — is evaluated against one manifest and
 * committed as one conditional write. Two concurrent revocations of the same snapshot cannot both
 * report that they stopped the serving, and a revocation cannot take down a marketplace a later
 * approval has already moved on.
 */
public final class ObjectStoreGitStorage implements GitStorage, AutoCloseable {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_REF_PREFIX = "refs/snapshots/";

    /** The three roles, which are three key prefixes. */
    enum Role {
        QUARANTINE,
        HOSTED,
        PUBLISHED;

        String path() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ObjectStoreClient store;
    private final SkillsGatewayProperties.Cache cacheSettings;
    private final String prefix;
    private final PackCache cache;
    private final Path scratch;
    private final ObjectStoreStatistics statistics;

    public ObjectStoreGitStorage(
            ObjectStoreClient store, SkillsGatewayProperties properties, ObjectStoreStatistics statistics)
            throws IOException {
        SkillsGatewayProperties.ObjectStore settings = properties.storage().objectStore();
        this.store = store;
        this.statistics = statistics;
        this.cacheSettings = settings.cache();
        this.prefix = normalisePrefix(settings.prefix());
        Path cacheRoot = cacheSettings.dir() != null
                ? cacheSettings.dir()
                : properties.dataDir().resolve("object-store-cache");
        this.cache = new PackCache(cacheRoot.resolve("packs"), cacheSettings.maxBytes(), statistics);
        this.scratch = cacheRoot.resolve("scratch");
    }

    /** Refuse to run against a store that cannot serialize reference transitions. */
    public void probe() throws IOException {
        store.probe();
    }

    @Override
    public Repository quarantine(String marketplace) throws IOException {
        return openOrCreate(Role.QUARANTINE, marketplace);
    }

    @Override
    public Repository hosted(String marketplace) throws IOException {
        return openOrCreate(Role.HOSTED, marketplace);
    }

    @Override
    public Optional<Repository> hostedIfPresent(String marketplace) throws IOException {
        return openIfPresent(Role.HOSTED, marketplace);
    }

    @Override
    public Repository published(String marketplace) throws IOException {
        return openOrCreate(Role.PUBLISHED, marketplace);
    }

    @Override
    public Optional<Repository> publishedIfServing(String marketplace) throws IOException {
        Optional<Repository> repository = openIfPresent(Role.PUBLISHED, marketplace);
        if (repository.isEmpty()) {
            return repository;
        }
        if (repository.get().resolve(MAIN) == null) {
            repository.get().close();
            return Optional.empty();
        }
        return repository;
    }

    @Override
    @Requirements({"GW_0112"})
    public boolean unpublish(String marketplace, String sha) throws IOException {
        ManifestStore manifests = manifests(Role.PUBLISHED, marketplace);
        if (!manifests.exists()) {
            return false;
        }
        String pinned = SNAPSHOT_REF_PREFIX + sha;
        String description = "revocation of %s and of the served tip while it is still that snapshot, in %s"
                .formatted(pinned, marketplace);
        return manifests.transact(description, current -> {
            Map<String, String> edits = new HashMap<>();
            if (current.ref(pinned) != null) {
                // The pinned reference is advertised in its own right, so it goes unconditionally:
                // an approved snapshot stays fetchable by name even when it is not the tip.
                edits.put(pinned, null);
            }
            boolean stoppedTheServing = sha.equals(current.ref(MAIN));
            if (stoppedTheServing) {
                edits.put(MAIN, null);
            }
            if (edits.isEmpty()) {
                return ManifestStore.Step.unchanged(false);
            }
            return new ManifestStore.Step<>(current.withRefs(edits), stoppedTheServing);
        });
    }

    /** The counters this backend keeps about its own contention and caching. */
    public ObjectStoreStatistics statistics() {
        return statistics;
    }

    /**
     * Releases the store client's connection pool when the gateway shuts down. A client handed in
     * from outside — a test sharing one, say — keeps its own lifecycle and is left alone.
     */
    @Override
    public void close() throws Exception {
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Requirements({"GW_0112"})
    private Repository openOrCreate(Role role, String marketplace) throws IOException {
        ManifestStore manifests = manifests(role, marketplace);
        // HEAD is part of the manifest rather than an object of its own, so creating a repository
        // is one create-exactly-once write — and it links HEAD to the branch the gateway publishes
        // on, not to JGit's own default, or a client could not check out what it clones.
        manifests.createIfAbsent(RepositoryManifest.created(MAIN));
        return repository(role, marketplace, manifests);
    }

    private Optional<Repository> openIfPresent(Role role, String marketplace) throws IOException {
        ManifestStore manifests = manifests(role, marketplace);
        if (!manifests.exists()) {
            return Optional.empty();
        }
        return Optional.of(repository(role, marketplace, manifests));
    }

    private ObjectStoreRepository repository(Role role, String marketplace, ManifestStore manifests) {
        return new ObjectStoreRepository(
                new DfsRepositoryDescription(role.path() + "/" + marketplace),
                store,
                manifests,
                cache,
                scratch,
                repositoryPrefix(role, marketplace) + "objects/",
                role.path() + "-" + marketplace + "-",
                cacheSettings.blockSizeBytes());
    }

    private ManifestStore manifests(Role role, String marketplace) {
        String repositoryPrefix = repositoryPrefix(role, marketplace);
        return new ManifestStore(
                store,
                repositoryPrefix + "manifest",
                repositoryPrefix + "wal/",
                cacheSettings.refFreshness(),
                statistics);
    }

    String repositoryPrefix(Role role, String marketplace) {
        return prefix + role.path() + "/" + marketplace + "/";
    }

    /** How long a pack nothing references is kept before its objects may be deleted. */
    Duration packGrace() {
        return cacheSettings.packGrace();
    }

    ObjectStoreClient client() {
        return store;
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.strip();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
