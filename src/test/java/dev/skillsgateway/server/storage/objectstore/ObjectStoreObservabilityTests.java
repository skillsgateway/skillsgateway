package dev.skillsgateway.server.storage.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.observability.GitStorageHealthIndicator;
import dev.skillsgateway.server.observability.MeteredObjectStoreClient;
import dev.skillsgateway.server.observability.ObjectStoreMetrics;
import io.github.reqstool.annotations.SVCs;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * What an operator can see about the storage backend without reading its source.
 *
 * <p>The backend trades per-reference locking for a repository-wide compare-and-swap on the stated
 * ground that contention is low. That is an argument, not a measurement, and the design says so:
 * revisiting it needs the conflict and retry numbers. The same goes for the two levels compaction
 * holds down and for the latency of a store that is a network away. So these cases assert that the
 * numbers exist, that they move for the right reasons, and — the part that is easy to get wrong —
 * that none of them is tagged by anything unbounded.
 */
class ObjectStoreObservabilityTests {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_PREFIX = "refs/snapshots/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    // the backend's contention, levels and cache counters are all published as meters
    @Test
    @SVCs({"SVC_GW_0116"})
    void theBackendsCountersArePublishedAsMeters() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("metrics");
        ObjectStoreClient store = ObjectStoreTestSupport.client();
        ObjectStoreStatistics statistics = new ObjectStoreStatistics();
        String competing = SNAPSHOT_PREFIX + "c".repeat(40);
        ObjectStoreGitStorage storage = new ObjectStoreGitStorage(
                new RacingObjectStoreClient(store, competing, 1),
                ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1)),
                statistics);

        // One conflict, deliberately provoked by a disjoint writer slipping in ahead of the
        // conditional write, so the contention counters have something real to report.
        try (Repository published = storage.published(marketplace)) {
            ObjectId first = commit(published, "first");
            setRef(published, MAIN, first);
        }
        // Then compaction and a second transition through an unobstructed store, on the same
        // counters: the levels are what a maintenance pass leaves behind, not what a double does.
        ObjectStoreGitStorage plain = new ObjectStoreGitStorage(
                store, ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1)), statistics);
        String compacted = marketplace();
        long walBeforeCompaction;
        try (Repository published = plain.published(compacted)) {
            ObjectId second = commit(published, "second");
            setRef(published, SNAPSHOT_PREFIX + second.name(), second);
            walBeforeCompaction = statistics.walDepth();
            new ObjectStoreMaintenance(Duration.ofHours(1)).compact((ObjectStoreRepository) published);
        }

        MeterRegistry meters = new SimpleMeterRegistry();
        new ObjectStoreMetrics(statistics).bindTo(meters);

        assertThat(counter(meters, ObjectStoreMetrics.CONFLICTS))
                .as("a conditional write the store refused is the contention the design argues is rare")
                .isEqualTo(statistics.conflicts())
                .isGreaterThan(0);
        assertThat(counter(meters, ObjectStoreMetrics.RETRIES)).isEqualTo(statistics.retries());
        assertThat(counter(meters, ObjectStoreMetrics.EXHAUSTIONS)).isEqualTo(statistics.exhaustions());
        assertThat(counter(meters, ObjectStoreMetrics.REFRESHES)).isEqualTo(statistics.refreshes());
        assertThat(gauge(meters, ObjectStoreMetrics.LIVE_PACKS))
                .as("the pack count compaction exists to hold down has to be readable, or it is held down on faith")
                .isEqualTo(statistics.livePacks())
                .isGreaterThan(0);
        assertThat(gauge(meters, ObjectStoreMetrics.WAL_DEPTH))
                .as("a maintenance pass counted the write-ahead entries it folded, and left the count behind")
                .isEqualTo(statistics.walDepth())
                .isLessThan(walBeforeCompaction);
        assertThat(counter(meters, ObjectStoreMetrics.PACK_DOWNLOADS)).isEqualTo(statistics.packDownloads());
        assertThat(counter(meters, ObjectStoreMetrics.PACK_CACHE_HITS)).isEqualTo(statistics.packCacheHits());
    }

    // every object-store request is timed, by operation and outcome and by nothing else
    @Test
    @SVCs({"SVC_GW_0116"})
    void everyObjectStoreRequestIsTimed() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("latency");
        MeterRegistry meters = new SimpleMeterRegistry();
        ObjectStoreClient store = new MeteredObjectStoreClient(ObjectStoreTestSupport.client(), meters);
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(store, prefix);

        try (Repository published = storage.published(marketplace)) {
            ObjectId tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);
            assertThat(storage.unpublish(marketplace, tip.name())).isTrue();
        }

        List<Meter> timers = meters.getMeters().stream()
                .filter(meter ->
                        MeteredObjectStoreClient.REQUESTS.equals(meter.getId().getName()))
                .toList();
        assertThat(timers)
                .as("a slow approval and a slow bucket are the same symptom until the requests are timed")
                .isNotEmpty();
        assertThat(timers.stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .map(Tag::getKey))
                .as("operation and outcome are closed vocabularies; an object key would not be")
                .containsOnly("operation", "outcome");
        assertThat(timers.stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .filter(tag -> "operation".equals(tag.getKey()))
                        .map(Tag::getValue))
                .contains("get", "put", "conditional-put", "create");
        assertThat(meters.find(MeteredObjectStoreClient.REQUESTS).timers().stream()
                        .mapToLong(timer -> timer.count())
                        .sum())
                .isGreaterThan(0);
    }

    // no meter this backend publishes is tagged by a marketplace, a repository or a key
    @Test
    @SVCs({"SVC_GW_0116"})
    void noMeterCarriesAnUnboundedTag() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("cardinality");
        MeterRegistry meters = new SimpleMeterRegistry();
        ObjectStoreStatistics statistics = new ObjectStoreStatistics();
        ObjectStoreClient store = new MeteredObjectStoreClient(ObjectStoreTestSupport.client(), meters);
        ObjectStoreGitStorage storage = new ObjectStoreGitStorage(
                store, ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1)), statistics);
        try (Repository published = storage.published(marketplace)) {
            setRef(published, MAIN, commit(published, "approved"));
        }
        new ObjectStoreMetrics(statistics).bindTo(meters);

        assertThat(meters.getMeters().stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .map(Tag::getValue))
                .as("a time series per marketplace is unbounded cardinality on a question the adoption API answers")
                .doesNotContain(marketplace, prefix);
        assertThat(meters.getMeters().stream()
                        .flatMap(meter -> meter.getId().getTags().stream())
                        .map(Tag::getKey))
                .allSatisfy(key -> assertThat(key).isIn("operation", "outcome"));
    }

    // the health indicator names the object-store backend, its reachability and the startup probe
    @Test
    @SVCs({"SVC_GW_0116"})
    void theHealthIndicatorReportsTheObjectStoreBackend() throws Exception {
        String prefix = ObjectStoreTestSupport.isolatedPrefix("health");
        SkillsGatewayProperties properties =
                ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1));
        ObjectStoreGitStorage storage =
                new ObjectStoreGitStorage(ObjectStoreTestSupport.client(), properties, new ObjectStoreStatistics());
        storage.probe();

        Health health = new GitStorageHealthIndicator(storage, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("backend", "object-store")
                .containsEntry("bucket", ObjectStoreTestSupport.BUCKET)
                .containsEntry("conditionalWrites", "verified at startup");
    }

    /**
     * The case the indicator exists for. A fetch against a bucket that cannot be reached looks to
     * the client exactly like a broken repository, so if the gateway does not say the bucket is
     * unreachable, nothing does.
     */
    // a store that cannot be reached is reported down, not up
    @Test
    @SVCs({"SVC_GW_0116"})
    void anUnreachableStoreIsReportedDown() throws Exception {
        String prefix = ObjectStoreTestSupport.isolatedPrefix("unreachable");
        SkillsGatewayProperties properties =
                ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1));
        ObjectStoreGitStorage storage =
                new ObjectStoreGitStorage(new UnreachableObjectStoreClient(), properties, new ObjectStoreStatistics());

        Health health = new GitStorageHealthIndicator(storage, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("backend", "object-store");
    }

    // on the filesystem backend the indicator says so, and reports the directory it depends on
    @Test
    @SVCs({"SVC_GW_0116"})
    void theHealthIndicatorReportsTheFilesystemBackend() throws Exception {
        Path data = Files.createTempDirectory("git-storage-health-");
        SkillsGatewayProperties properties = new SkillsGatewayProperties(
                data, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        Health health = new GitStorageHealthIndicator(
                        new dev.skillsgateway.server.storage.FilesystemGitStorage(properties), properties)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("backend", "filesystem").containsEntry("dataDir", data);
    }

    // --- helpers --------------------------------------------------------------------------------

    /** A store that is configured, plausible, and simply not there. */
    private static final class UnreachableObjectStoreClient implements ObjectStoreClient {
        @Override
        public List<String> list(String prefix) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public java.util.Optional<StoredObject> get(String key) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public java.util.Optional<StoredObject> getIfChanged(String key, String etag) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public java.io.InputStream open(String key) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public long size(String key) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public boolean exists(String key) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public String put(String key, byte[] body) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public String putFile(String key, Path file) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public java.util.Optional<String> putIfMatch(String key, byte[] body, String etag) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public java.util.Optional<String> putIfAbsent(String key, byte[] body) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public void delete(String key) throws IOException {
            throw new IOException("the bucket could not be reached");
        }

        @Override
        public void probe() throws IOException {
            throw new IOException("the bucket could not be reached");
        }
    }

    private static double counter(MeterRegistry meters, String name) {
        return meters.get(name).functionCounter().count();
    }

    private static double gauge(MeterRegistry meters, String name) {
        return meters.get(name).gauge().value();
    }

    private static String marketplace() {
        return "observability-" + COUNTER.incrementAndGet();
    }

    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = inserter.insert(new TreeFormatter());
            PersonIdent who = new PersonIdent("Meter", "meter@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(tree);
            builder.setAuthor(who);
            builder.setCommitter(who);
            builder.setMessage(message);
            ObjectId commit = inserter.insert(builder);
            inserter.flush();
            return commit;
        }
    }

    private static void setRef(Repository repository, String ref, ObjectId to) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(to);
        assertThat(update.forceUpdate())
                .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
    }
}
