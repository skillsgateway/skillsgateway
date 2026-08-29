package dev.skillsgateway.server.storage.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;

/**
 * What several writers on one bucket must not be able to do to each other.
 *
 * <p>The backend's own suite proves the mechanism — one manifest, one conditional write, a bounded
 * retry. These cases are about the outcomes that mechanism exists to guarantee on the path where
 * getting them wrong is worst: a revocation and the approval that superseded it arriving at the
 * same instant, a snapshot revoked long after something else took over the serving, and a writer
 * that dies in the window between its objects becoming durable and anything naming them.
 *
 * <p>Every case here runs against the real store through the Floci dev service, because the one
 * primitive the whole design rests on is the store's conditional write; a double that stood in for
 * it would be a double that could certify a broken backend green.
 */
class ObjectStoreConcurrencyTests {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_PREFIX = "refs/snapshots/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /**
     * The interleaving the design's own prose calls out: "a revocation cannot take down a
     * marketplace that a later approval has already moved on". One replica revokes the snapshot
     * currently on the wire while another publishes its successor and moves the served tip to it.
     *
     * <p>Compare-and-swap is what decides this, and what it must decide is that <em>one</em> of the
     * two writers determined the outcome. Either the publication landed first, in which case the
     * revocation finds the tip has moved and reports it stopped nothing; or the revocation landed
     * first, in which case the publication's reference-level precondition genuinely no longer holds
     * and it must be <em>refused out loud</em>, so its caller can retry, rather than dropped. Both
     * succeeding, or both being lost, would each be a lost update on the served surface.
     *
     * <p>The pinned reference for the successor is a disjoint reference and must land on every
     * interleaving — that is the retry the manifest-versus-reference precondition mismatch exists
     * to absorb — and a publisher that retries a refusal converges on serving the successor.
     */
    // a revocation and the approval that supersedes it: exactly one of them decides the outcome
    @Test
    @SVCs({"SVC_GW_0112"})
    void aRevocationAndTheApprovalThatSupersedesItAreDecidedByExactlyOneOfThem() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            int round = attempt;
            String marketplace = marketplace();
            String prefix = ObjectStoreTestSupport.isolatedPrefix("supersede");
            ObjectStoreGitStorage revoker = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);
            ObjectStoreGitStorage publisher = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);

            ObjectId first;
            ObjectId second;
            try (Repository published = publisher.published(marketplace)) {
                first = commit(published, "first approval");
                second = commit(published, "second approval");
                setRef(published, SNAPSHOT_PREFIX + first.name(), first);
                setRef(published, MAIN, first);
            }

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            boolean stoppedTheServing;
            RefUpdate.Result servedTip;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<Boolean> revocation = pool.submit(() -> {
                    ready.countDown();
                    go.await(1, TimeUnit.MINUTES);
                    return revoker.unpublish(marketplace, first.name());
                });
                Future<RefUpdate.Result> publication = pool.submit(() -> {
                    try (Repository published = publisher.published(marketplace)) {
                        RefUpdate update = published.updateRef(MAIN);
                        update.setNewObjectId(second);
                        update.setForceUpdate(true);
                        ready.countDown();
                        go.await(1, TimeUnit.MINUTES);
                        setRef(published, SNAPSHOT_PREFIX + second.name(), second);
                        return update.forceUpdate();
                    }
                });
                assertThat(ready.await(1, TimeUnit.MINUTES)).isTrue();
                go.countDown();
                servedTip = publication.get(2, TimeUnit.MINUTES);
                stoppedTheServing = revocation.get(2, TimeUnit.MINUTES);
            }

            assertThat(servedTip)
                    .as("round %d: a publication is applied or refused out loud, never quietly dropped", round)
                    .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.LOCK_FAILURE);
            assertThat(stoppedTheServing)
                    .as(
                            "round %d: exactly one of the two decided the served tip — a revocation reports it"
                                    + " stopped the serving on precisely the interleavings where the publication"
                                    + " could not proceed",
                            round)
                    .isEqualTo(servedTip == RefUpdate.Result.LOCK_FAILURE);

            try (Repository repository = revoker.published(marketplace)) {
                assertThat(repository.exactRef(SNAPSHOT_PREFIX + first.name()))
                        .as("round %d: the revoked snapshot is off the wire by name, whoever won the race", round)
                        .isNull();
                assertThat(repository.exactRef(SNAPSHOT_PREFIX + second.name()))
                        .as(
                                "round %d: the successor's pinned reference is disjoint, so the loser of the manifest"
                                        + " race retries onto it rather than losing it",
                                round)
                        .isNotNull();
                if (servedTip == RefUpdate.Result.LOCK_FAILURE) {
                    // The refusal is the caller's cue, and the retry is what the bound is worth:
                    // one re-read and one conditional write, on state nobody is contending any more.
                    setRef(repository, MAIN, second);
                }
            }

            try (Repository serving = publisher
                    .publishedIfServing(marketplace)
                    .orElseThrow(() -> new AssertionError(
                            "round " + round + ": the marketplace is not serving the snapshot that superseded the"
                                    + " revoked one, though the publication was applied or retried"))) {
                assertThat(serving.resolve(MAIN)).isEqualTo(second);
            }
        }
    }

    /**
     * The same guarantee without a race to depend on: once something else is being served, no
     * number of concurrent revocations of the superseded snapshot may report that any of them
     * stopped the serving, because none of them did.
     */
    // concurrent revocations of a superseded snapshot all report they stopped nothing
    @Test
    @SVCs({"SVC_GW_0112"})
    void concurrentRevocationsOfASupersededSnapshotStopNothing() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("superseded");
        ObjectStoreGitStorage publisher = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);
        ObjectId superseded;
        ObjectId serving;
        try (Repository published = publisher.published(marketplace)) {
            superseded = commit(published, "an approval that was replaced");
            serving = commit(published, "the approval that replaced it");
            setRef(published, SNAPSHOT_PREFIX + superseded.name(), superseded);
            setRef(published, SNAPSHOT_PREFIX + serving.name(), serving);
            setRef(published, MAIN, serving);
        }

        int replicas = 6;
        List<ObjectStoreGitStorage> storages = new ArrayList<>();
        for (int i = 0; i < replicas; i++) {
            storages.add(ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix));
        }

        List<Boolean> answers = raceAll(replicas, i -> storages.get(i).unpublish(marketplace, superseded.name()));

        assertThat(answers)
                .as("nothing here stopped the marketplace serving, so nothing may say it did")
                .containsOnly(false);
        try (Repository repository = publisher.published(marketplace)) {
            assertThat(repository.resolve(MAIN)).isEqualTo(serving);
            assertThat(repository.exactRef(SNAPSHOT_PREFIX + superseded.name()))
                    .as("the pinned reference goes unconditionally, however many callers asked for it")
                    .isNull();
            assertThat(repository.exactRef(SNAPSHOT_PREFIX + serving.name())).isNotNull();
        }
    }

    /**
     * Publications and revocations of distinct snapshots interleaved, which is what a retention
     * sweep running while approvals land actually looks like. Compare-and-swap serializes them;
     * what it must not do is drop one, and the reference set is the whole answer.
     */
    // publications and revocations of distinct snapshots interleave without losing any of them
    @Test
    @SVCs({"SVC_GW_0112"})
    void interleavedPublicationsAndRevocationsLoseNothing() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("interleaved");
        ObjectStoreGitStorage storage = ObjectStoreTestSupport.storage(ObjectStoreTestSupport.client(), prefix);

        List<ObjectId> existing = new ArrayList<>();
        try (Repository published = storage.published(marketplace)) {
            for (int i = 0; i < 4; i++) {
                ObjectId tip = commit(published, "existing approval " + i);
                setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
                existing.add(tip);
            }
        }
        List<ObjectId> arriving = new ArrayList<>();
        try (Repository published = storage.published(marketplace)) {
            for (int i = 0; i < 4; i++) {
                arriving.add(commit(published, "arriving approval " + i));
            }
        }

        raceAll(8, i -> {
            if (i < 4) {
                return storage.unpublish(marketplace, existing.get(i).name());
            }
            try (Repository published = storage.published(marketplace)) {
                ObjectId tip = arriving.get(i - 4);
                setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
                return true;
            }
        });

        try (Repository published = storage.published(marketplace)) {
            assertThat(published.getRefDatabase().getRefsByPrefix(SNAPSHOT_PREFIX).stream()
                            .map(Ref::getName))
                    .as("every revocation took effect and every publication survived it")
                    .containsExactlyInAnyOrderElementsOf(arriving.stream()
                            .map(id -> SNAPSHOT_PREFIX + id.name())
                            .toList());
        }
    }

    /**
     * The durability order, proved by breaking it in the only place it can break. The writer's
     * packs are already in the bucket when it dies; the manifest that would have named them never
     * lands. What must be true afterwards is that the repository is exactly what it was — no
     * reference reaching objects nothing published, no manifest naming a pack that is not there —
     * and that the next writer is unaffected.
     */
    // a writer killed between uploading its objects and naming them leaves nothing half-published
    @Test
    @SVCs({"SVC_GW_0112"})
    void aWriterKilledBeforeTheManifestWriteNamesNothing() throws Exception {
        String marketplace = marketplace();
        String prefix = ObjectStoreTestSupport.isolatedPrefix("killed");
        ObjectStoreClient store = ObjectStoreTestSupport.client();
        CrashingObjectStoreClient dying = new CrashingObjectStoreClient(store);
        ObjectStoreGitStorage doomed = ObjectStoreTestSupport.storage(dying, prefix);
        ObjectStoreGitStorage survivor = ObjectStoreTestSupport.storage(store, prefix);

        ObjectId approved;
        try (Repository published = survivor.published(marketplace)) {
            approved = commit(published, "an approval that landed");
            setRef(published, SNAPSHOT_PREFIX + approved.name(), approved);
            setRef(published, MAIN, approved);
        }
        RepositoryManifest before = manifest(store, prefix, marketplace);

        ObjectId lost;
        try (Repository published = doomed.published(marketplace);
                ObjectInserter inserter = published.newObjectInserter()) {
            // Insert first, so the objects reach the bucket, and only then kill the writer: the
            // window this case is about is between durable objects and the manifest naming them.
            lost = insert(inserter, "an approval whose writer died");
            dying.kill();
            assertThatThrownBy(inserter::flush)
                    .as("a transition the store never accepted is raised, never reported as done")
                    .isInstanceOf(IOException.class);
        }

        RepositoryManifest after = manifest(store, prefix, marketplace);
        assertThat(after.sequence())
                .as("the manifest is the publication; a writer that never wrote it published nothing")
                .isEqualTo(before.sequence());
        assertThat(after.refs()).isEqualTo(before.refs());
        assertThat(after.refs().values())
                .as("and no reference reaches what the dead writer uploaded")
                .doesNotContain(lost.name());
        for (RepositoryManifest.PackEntry pack : after.packs()) {
            assertThat(store.exists(prefix + "published/" + marketplace + "/objects/" + pack.name() + ".pack"))
                    .as("every pack the manifest names is durable, which is the invariant the order exists for")
                    .isTrue();
        }

        try (Repository published = survivor.published(marketplace)) {
            assertThat(published.resolve(MAIN))
                    .as("the estate the dead writer found is the estate it left")
                    .isEqualTo(approved);
            ObjectId next = commit(published, "the approval after the crash");
            setRef(published, SNAPSHOT_PREFIX + next.name(), next);
            setRef(published, MAIN, next);
            assertThat(published.resolve(MAIN))
                    .as("and the next writer is not held up by the wreckage")
                    .isEqualTo(next);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Start {@code count} callers at the same instant and collect what each of them answered. */
    private static <T> List<T> raceAll(int count, ThrowingIntFunction<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<T> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Callable<T>> tasks = IntStream.range(0, count)
                    .<Callable<T>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await(1, TimeUnit.MINUTES);
                        return work.apply(i);
                    })
                    .toList();
            List<Future<T>> submitted = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(1, TimeUnit.MINUTES)).isTrue();
            go.countDown();
            for (Future<T> future : submitted) {
                answers.add(future.get(2, TimeUnit.MINUTES));
            }
        }
        return answers;
    }

    @FunctionalInterface
    private interface ThrowingIntFunction<T> {
        T apply(int index) throws Exception;
    }

    private static RepositoryManifest manifest(ObjectStoreClient client, String prefix, String marketplace)
            throws IOException {
        return RepositoryManifest.parse(client.get(prefix + "published/" + marketplace + "/manifest")
                .orElseThrow()
                .body());
    }

    private static String marketplace() {
        return "concurrency-" + COUNTER.incrementAndGet();
    }

    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId commit = insert(inserter, message);
            inserter.flush();
            return commit;
        }
    }

    /** Stage a commit without flushing, so a caller can choose when — or whether — it lands. */
    private static ObjectId insert(ObjectInserter inserter, String message) throws IOException {
        ObjectId tree = inserter.insert(new TreeFormatter());
        PersonIdent who = new PersonIdent("Racer", "racer@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
        CommitBuilder builder = new CommitBuilder();
        builder.setTreeId(tree);
        builder.setAuthor(who);
        builder.setCommitter(who);
        builder.setMessage(message);
        return inserter.insert(builder);
    }

    private static void setRef(Repository repository, String ref, ObjectId to) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(to);
        assertThat(update.forceUpdate())
                .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
    }
}
