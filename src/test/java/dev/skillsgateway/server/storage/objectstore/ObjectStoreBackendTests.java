package dev.skillsgateway.server.storage.objectstore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;

/**
 * What the object-store backend has to get right that the shared contract cannot ask for.
 *
 * <p>The contract suite proves the two backends are indistinguishable from above the seam. These
 * cases are about what happens <em>below</em> it, where the two are not alike at all: a
 * repository-level compare-and-swap standing in for per-reference locking, a reference map cached
 * on every replica, and packs that some other replica may be streaming while this one deletes them.
 * Each is a property design decision 10 named as a consequence to carry into the implementation
 * rather than to discover in it.
 */
class ObjectStoreBackendTests {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_PREFIX = "refs/snapshots/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    // --- the per-reference versus whole-manifest precondition mismatch -------------------------

    /**
     * The mismatch, reproduced deliberately. A writer touching one reference loses the manifest
     * race to a writer touching a different one; both preconditions are still valid, so the loser
     * must retry rather than report a failure. Returning false here would become
     * {@code LOCK_FAILURE} in {@code DfsRefUpdate}, which JGit does not retry, so the caller would
     * be told an update that should have succeeded had failed.
     */
    // a writer that loses the manifest race to a disjoint reference retries and succeeds
    @Test
    @SVCs({"SVC_GW_0112"})
    void aDisjointWriterLosingTheManifestRaceRetriesAndSucceeds() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("disjoint");
        String competing = SNAPSHOT_PREFIX + "b".repeat(40);
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreStatistics statistics = new ObjectStoreStatistics();
        ObjectStoreGitStorage storage = new ObjectStoreGitStorage(
                new RacingObjectStoreClient(client, competing, 1),
                ObjectStoreTestSupport.properties(prefix, Duration.ZERO, Duration.ofHours(1)),
                statistics);

        try (Repository published = storage.published(marketplace)) {
            ObjectId tip = commit(published, "approved");

            RefUpdate update = published.updateRef(SNAPSHOT_PREFIX + tip.name());
            update.setNewObjectId(tip);
            RefUpdate.Result result = update.forceUpdate();

            assertThat(result)
                    .as("a disjoint writer winning the manifest race must not become this caller's lock failure")
                    .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
            assertThat(published.exactRef(SNAPSHOT_PREFIX + tip.name())).isNotNull();
            assertThat(published.exactRef(competing))
                    .as("the disjoint writer's edit must survive: this is a retry, not a rewind")
                    .isNotNull();
        }
        assertThat(statistics.conflicts())
                .as("the conflict is real and must be counted, or contention stays invisible until it is an outage")
                .isEqualTo(1);
        assertThat(statistics.retries()).isEqualTo(1);
    }

    /**
     * And the other half, which is what makes the first half honest: when the reference the caller
     * read really has moved, the answer is a lock failure and not another retry.
     */
    // a reference that moved under the writer is a lock failure, not a retry
    @Test
    @SVCs({"SVC_GW_0112"})
    void aReferenceThatMovedIsALockFailure() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("moved");
        ObstructingObjectStoreClient client = new ObstructingObjectStoreClient(ObjectStoreTestSupport.client());
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(client, prefix);
        ObjectId tip;
        ObjectId next;
        try (Repository published = storage.published(marketplace)) {
            tip = commit(published, "approved");
            next = commit(published, "a second approval");
            setRef(published, MAIN, tip);
        }

        client.obstruct(prefix + "published/" + marketplace + "/manifest", MAIN);

        try (Repository published = storage.published(marketplace)) {
            RefUpdate update = published.updateRef(MAIN);
            update.setNewObjectId(next);
            update.setForceUpdate(true);

            assertThat(update.forceUpdate())
                    .as("the reference itself moved, which is the one thing that is not a retry")
                    .isEqualTo(RefUpdate.Result.LOCK_FAILURE);
        }
    }

    // --- one push, one transition -------------------------------------------------------------

    /**
     * Task 2.6, and what makes {@code performsAtomicTransactions()} true rather than merely
     * advertised: a whole {@code ReceiveCommand} list is evaluated against one manifest read and
     * committed as one conditional write. The manifest sequence is the evidence — three references
     * moving it by one means one transition, and by three would mean three chances to publish half
     * a push.
     */
    // a whole push of many references is one manifest transition
    @Test
    @SVCs({"SVC_GW_0112"})
    void aWholePushIsOneManifestTransition() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("push");
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(client, prefix);

        try (Repository hosted = storage.hosted(marketplace)) {
            ObjectId one = commit(hosted, "one");
            ObjectId two = commit(hosted, "two");
            ObjectId three = commit(hosted, "three");
            long before = manifest(client, prefix, "hosted", marketplace).sequence();

            List<ReceiveCommand> commands = List.of(
                    new ReceiveCommand(ObjectId.zeroId(), one, MAIN),
                    new ReceiveCommand(ObjectId.zeroId(), two, SNAPSHOT_PREFIX + two.name()),
                    new ReceiveCommand(ObjectId.zeroId(), three, SNAPSHOT_PREFIX + three.name()));
            execute(hosted, commands, true);

            assertThat(commands)
                    .allSatisfy(command -> assertThat(command.getResult()).isEqualTo(ReceiveCommand.Result.OK));
            assertThat(manifest(client, prefix, "hosted", marketplace).sequence() - before)
                    .as("three references, one transition; anything else can publish half a push")
                    .isEqualTo(1);
        }
    }

    // an atomic push with one refused command writes nothing at all
    @Test
    @SVCs({"SVC_GW_0112"})
    void anAtomicPushWithOneRefusedCommandWritesNothing() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("atomic");
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(client, prefix);

        try (Repository hosted = storage.hosted(marketplace)) {
            ObjectId one = commit(hosted, "one");
            ObjectId two = commit(hosted, "two");
            long before = manifest(client, prefix, "hosted", marketplace).sequence();

            List<ReceiveCommand> commands = List.of(
                    new ReceiveCommand(ObjectId.zeroId(), one, MAIN),
                    // Claims a reference already exists at `one` when it does not exist at all.
                    new ReceiveCommand(one, two, SNAPSHOT_PREFIX + two.name()));
            execute(hosted, commands, true);

            assertThat(commands).allSatisfy(command -> assertThat(command.getResult())
                    .as("atomic means all or nothing, including the command that was fine")
                    .isEqualTo(ReceiveCommand.Result.LOCK_FAILURE));
            assertThat(hosted.exactRef(MAIN)).isNull();
            assertThat(manifest(client, prefix, "hosted", marketplace).sequence())
                    .as("a refused atomic push must leave the manifest untouched")
                    .isEqualTo(before);
        }
    }

    // --- the cross-replica revocation bound ---------------------------------------------------

    /**
     * The trust-boundary case. Two replicas, one bucket. The second has already read and cached the
     * reference map — which is exactly the state in which an unpublished snapshot would keep being
     * advertised — and must stop serving it within the stated bound, which at the default freshness
     * of zero is its very next reference advertisement.
     */
    // a snapshot revoked on one replica stops being served by another within the bound
    @Test
    @SVCs({"SVC_GW_0112"})
    void aRevokedSnapshotStopsBeingServedByAnotherReplicaWithinTheBound() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("replicas");
        ObjectStoreGitStorage one = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);
        ObjectStoreGitStorage two = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);

        ObjectId tip;
        try (Repository published = one.published(marketplace)) {
            tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);
        }

        try (Repository onTheOtherReplica = open(two.publishedIfServing(marketplace))) {
            assertThat(advertised(onTheOtherReplica))
                    .as("the second replica is serving it, and its reference cache is now warm")
                    .contains(MAIN, SNAPSHOT_PREFIX + tip.name());

            assertThat(one.unpublish(marketplace, tip.name())).isTrue();

            assertThat(advertised(onTheOtherReplica))
                    .as("a warm cache must not be able to keep an unpublished snapshot on the wire")
                    .isEmpty();
        }
        assertThat(two.publishedIfServing(marketplace)).isEmpty();
    }

    // --- pack deletion waits ------------------------------------------------------------------

    /**
     * A pack that stops being referenced is not deleted at that moment: another replica may be
     * part-way through streaming it, and a 404 in the middle of a fetch is indistinguishable from
     * corruption to the client that receives it. So the pack is tombstoned, kept for the grace
     * period, and only then deleted.
     */
    // a pack nothing references is kept for the grace period and deleted after it
    @Test
    @SVCs({"SVC_GW_0112"})
    void aPackNothingReferencesIsKeptForTheGracePeriod() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("grace");
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreGitStorage storage =
                ObjectStoreTestSupport.storage(client, prefix, Duration.ZERO, Duration.ofHours(1));

        try (Repository published = storage.published(marketplace)) {
            ObjectId first = commit(published, "first");
            setRef(published, MAIN, first);
            ObjectId second = commit(published, "second");
            setRef(published, MAIN, second);

            new ObjectStoreMaintenance(Duration.ofHours(1)).compact((ObjectStoreRepository) published);

            RepositoryManifest afterCompaction = manifest(client, prefix, "published", marketplace);
            assertThat(afterCompaction.tombstones())
                    .as("compaction has to leave something behind to wait on, or the grace period is theatre")
                    .isNotEmpty();
            String tombstoned = afterCompaction.tombstones().keySet().iterator().next();
            String packKey = prefix + "published/" + marketplace + "/objects/" + tombstoned + ".pack";
            assertThat(client.exists(packKey))
                    .as("within the grace period the objects must still be there for a fetch already in flight")
                    .isTrue();

            List<String> deleted =
                    new ObjectStoreMaintenance(Duration.ZERO).deleteExpiredPacks((ObjectStoreRepository) published);

            assertThat(deleted).contains(tombstoned);
            assertThat(client.exists(packKey))
                    .as("past the grace period the objects go, or the bucket grows without bound")
                    .isFalse();
            assertThat(manifest(client, prefix, "published", marketplace).tombstones())
                    .doesNotContainKey(tombstoned);
            assertThat(published.resolve(MAIN))
                    .as("compaction moves bytes, never references")
                    .isEqualTo(second);
        }
    }

    // write-ahead entries the manifest has absorbed are collected
    @Test
    @SVCs({"SVC_GW_0112"})
    void foldedWriteAheadEntriesAreCollected() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("wal");
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(client, prefix);
        String walPrefix = prefix + "published/" + marketplace + "/wal/";

        try (Repository published = storage.published(marketplace)) {
            ObjectId tip = commit(published, "approved");
            setRef(published, MAIN, tip);
            assertThat(client.list(walPrefix))
                    .as("every accepted transition writes its intent before its outcome")
                    .isNotEmpty();

            new ObjectStoreMaintenance(Duration.ZERO).foldWriteAheadLog((ObjectStoreRepository) published);

            assertThat(client.list(walPrefix))
                    .as("an entry the manifest already records is not needed to say what the repository is")
                    .isEmpty();
            assertThat(published.resolve(MAIN)).isEqualTo(tip);
        }
    }

    // --- durability order ---------------------------------------------------------------------

    // a pack is durable in the bucket before any reference can reach it
    @Test
    @SVCs({"SVC_GW_0112"})
    void aPackIsDurableBeforeAnyReferenceReachesIt() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("durability");
        ObjectStoreClient client = ObjectStoreTestSupport.client();
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(client, prefix);

        try (Repository published = storage.published(marketplace)) {
            ObjectId tip = commit(published, "inserted but not yet approved");

            RepositoryManifest manifest = manifest(client, prefix, "published", marketplace);
            assertThat(manifest.packs()).isNotEmpty();
            for (RepositoryManifest.PackEntry pack : manifest.packs()) {
                assertThat(client.exists(prefix + "published/" + marketplace + "/objects/" + pack.name() + ".pack"))
                        .as("the objects are in the bucket before anything names them")
                        .isTrue();
            }
            assertThat(manifest.refs().values())
                    .as("and no reference reaches them yet")
                    .doesNotContain(tip.name());
        }
    }

    // --- concurrency --------------------------------------------------------------------------

    /**
     * The property the whole design rests on, on the path where getting it wrong is worst: several
     * replicas revoking the same snapshot at once. Exactly one of them may be told its removal is
     * what stopped the marketplace serving, because that is what the caller writes on the ledger.
     */
    // exactly one of many concurrent revocations reports that it stopped the serving
    @Test
    @SVCs({"SVC_GW_0112"})
    void exactlyOneConcurrentRevocationReportsThatItStoppedTheServing() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("revocations");
        ObjectStoreGitStorage publisher = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);
        ObjectId tip;
        try (Repository published = publisher.published(marketplace)) {
            tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);
        }

        int replicas = 6;
        List<ObjectStoreGitStorage> storages = new java.util.ArrayList<>();
        for (int i = 0; i < replicas; i++) {
            storages.add(ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix));
        }
        CountDownLatch ready = new CountDownLatch(replicas);
        CountDownLatch go = new CountDownLatch(1);

        long stoppedTheServing;
        try (ExecutorService pool = Executors.newFixedThreadPool(replicas)) {
            List<Callable<Boolean>> tasks = IntStream.range(0, replicas)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await(1, TimeUnit.MINUTES);
                        return storages.get(i).unpublish(marketplace, tip.name());
                    })
                    .toList();
            List<Future<Boolean>> submitted = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(1, TimeUnit.MINUTES)).isTrue();
            go.countDown();
            stoppedTheServing = 0;
            for (Future<Boolean> future : submitted) {
                if (Boolean.TRUE.equals(future.get(2, TimeUnit.MINUTES))) {
                    stoppedTheServing++;
                }
            }
        }

        assertThat(stoppedTheServing)
                .as("two callers cannot both have been the one that stopped the marketplace serving")
                .isEqualTo(1);
        assertThat(publisher.publishedIfServing(marketplace)).isEmpty();
    }

    // concurrent publications of distinct snapshots all survive
    @Test
    @SVCs({"SVC_GW_0112"})
    void concurrentPublicationsOfDistinctSnapshotsAllSurvive() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("publications");
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);
        storage.published(marketplace).close();

        int writers = 6;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<String> published;
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Callable<String>> tasks = IntStream.range(0, writers)
                    .<Callable<String>>mapToObj(i -> () -> {
                        try (Repository repository = storage.published(marketplace)) {
                            ObjectId tip = commit(repository, "approval " + i);
                            ready.countDown();
                            go.await(1, TimeUnit.MINUTES);
                            setRef(repository, SNAPSHOT_PREFIX + tip.name(), tip);
                            return SNAPSHOT_PREFIX + tip.name();
                        }
                    })
                    .toList();
            List<Future<String>> submitted = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(1, TimeUnit.MINUTES)).isTrue();
            go.countDown();
            published = new java.util.ArrayList<>();
            for (Future<String> future : submitted) {
                published.add(future.get(2, TimeUnit.MINUTES));
            }
        }

        try (Repository repository = storage.published(marketplace)) {
            assertThat(repository.getRefDatabase().getRefsByPrefix(SNAPSHOT_PREFIX).stream()
                            .map(Ref::getName))
                    .as("compare-and-swap serializes writers; it must not lose any of them")
                    .containsExactlyInAnyOrderElementsOf(published);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static void execute(Repository repository, List<ReceiveCommand> commands, boolean atomic)
            throws IOException {
        BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
        batch.setAtomic(atomic);
        batch.setAllowNonFastForwards(true);
        batch.addCommand(commands);
        try (RevWalk walk = new RevWalk(repository)) {
            batch.execute(walk, NullProgressMonitor.INSTANCE);
        }
    }

    private static List<String> advertised(Repository repository) throws IOException {
        return repository.getRefDatabase().getRefsByPrefix("refs/").stream()
                .map(Ref::getName)
                .toList();
    }

    private static RepositoryManifest manifest(ObjectStoreClient client, String prefix, String role, String marketplace)
            throws IOException {
        return RepositoryManifest.parse(client.get(prefix + role + "/" + marketplace + "/manifest")
                .orElseThrow()
                .body());
    }

    private static Repository open(Optional<Repository> repository) {
        return repository.orElseThrow(() -> new AssertionError("expected the repository to be present"));
    }

    private static String marketplace() {
        return "object-store-" + COUNTER.incrementAndGet();
    }

    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = inserter.insert(new TreeFormatter());
            PersonIdent who = new PersonIdent("Backend", "backend@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
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
