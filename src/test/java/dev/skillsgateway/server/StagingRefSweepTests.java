package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.StagingRefSightingRepository;
import dev.skillsgateway.server.retention.RetentionService;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sweep of publication staging references a crash left behind (GW_0168), on both backends.
 *
 * <p>Publication stages a snapshot's objects in the published repository under
 * {@code refs/staging/<sha>} and only then moves the served references. A process killed between
 * the two leaves the staging reference behind, serving nothing and pinning everything it points at.
 *
 * <p>Three of the four cases here are <em>survival</em> cases, and that ratio is the point. Removing
 * a reference too late costs disk, which is the cost that already exists; removing one too early
 * collects a live publication's objects and leaves a marketplace published at a commit whose
 * content is gone. So the in-flight case does not stop at "the reference is still there" — it goes
 * on to finish the publication and read the content back off the served tip.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StagingRefSweepTests extends AbstractGatewayTest {

    private static final String STAGING = GitStorage.STAGING_REF_PREFIX;
    private static final String SNAPSHOT_REF_PREFIX = "refs/snapshots/";
    private static final String MAIN = Constants.R_HEADS + "main";

    /** Comfortably past the 24h default, so the fixture does not encode the default's value. */
    private static final Duration LONG_AGO = Duration.ofDays(3);

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private GitStorage storage;

    @Autowired
    private StagingRefSightingRepository sightings;

    @Autowired
    private AdminAuditLogger auditLogger;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private SkillsGatewayProperties properties;

    private GitStorage objectStoreStorage;

    /**
     * One backend under test, with the retention service that reads it.
     *
     * <p>The gateway's own service reads the context's filesystem backend; the object-store one is
     * the same service constructed over a bucket-backed seam and the same database. That is what
     * makes this a statement about the sweep rather than about either backend: only the
     * {@link GitStorage} differs.
     */
    private record Backend(String name, GitStorage storage, RetentionService retention) {
        @Override
        public String toString() {
            return name;
        }
    }

    private List<Backend> backends() {
        return List.of(
                new Backend("filesystem", storage, retentionService),
                new Backend("object-store", objectStore(), retentionOver(objectStore(), properties)));
    }

    private synchronized GitStorage objectStore() {
        if (objectStoreStorage == null) {
            try {
                objectStoreStorage = ObjectStoreTestSupport.storage(
                        ObjectStoreTestSupport.client(), ObjectStoreTestSupport.isolatedPrefix("staging-sweep"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return objectStoreStorage;
    }

    private RetentionService retentionOver(GitStorage over, SkillsGatewayProperties settings) {
        return new RetentionService(
                marketplaceRepository, snapshotRepository, sightings, over, auditLogger, webhookService, settings);
    }

    // --- the four properties, on both backends --------------------------------------------------

    /**
     * The leak itself. The abandoned reference goes, and its commit is left reachable from nothing —
     * which is what "collectable" means portably, since the two backends reclaim on different
     * clocks. That the filesystem one reclaims immediately is asserted separately below.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0168"})
    void anAbandonedStagingReferencePastTheBoundIsSweptAndItsCommitBecomesUnreachable(Backend backend)
            throws IOException {
        String name = uniqueName("abandoned");
        ObjectId served;
        ObjectId abandoned;
        try (Repository published = backend.storage().published(name)) {
            served = commit(published, "served");
            setRef(published, MAIN, served);
            setRef(published, SNAPSHOT_REF_PREFIX + served.name(), served);
            abandoned = commit(published, "staged and never published");
            setRef(published, STAGING + abandoned.name(), abandoned);
        }
        seenLongAgo(name, STAGING + abandoned.name());

        RetentionService.PassResult result = backend.retention().sweepStagingRefs("alice");

        assertThat(result.acted()).isPositive();
        assertThat(refs(backend.storage(), name)).doesNotContainKey(STAGING + abandoned.name());
        assertThat(reachable(backend.storage(), name, abandoned)).isFalse();
        // The served pair is what the facade advertises, and neither of them moved.
        assertThat(refs(backend.storage(), name))
                .containsEntry(MAIN, served)
                .containsEntry(SNAPSHOT_REF_PREFIX + served.name(), served);
        assertThat(reachable(backend.storage(), name, served)).isTrue();
    }

    /**
     * The case the bound exists for: a publication that has staged its objects and whose snapshot
     * row the sweep has not read — here, does not have at all. The reference survives, and the
     * proof that survival was worth anything is that the publication then completes and the content
     * is readable off the served tip.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0168"})
    void anInFlightPublicationInsideTheBoundSurvivesAndStillCompletes(Backend backend) throws IOException {
        String name = uniqueName("inflight");
        ObjectId staged;
        try (Repository published = backend.storage().published(name)) {
            staged = commit(published, "objects transferred, transition not yet made");
            setRef(published, STAGING + staged.name(), staged);
        }
        // No sighting is seeded and no snapshot row exists: this is the sweep's first look at a
        // reference a publication wrote moments ago, which is exactly the race.

        backend.retention().sweepStagingRefs("alice");

        assertThat(refs(backend.storage(), name)).containsEntry(STAGING + staged.name(), staged);

        // And the publication finishes, which is the assertion that matters: the objects are still
        // there to be served.
        boolean startedServing = backend.storage().commitPublication(name, staged.name());
        assertThat(startedServing).isTrue();
        assertThat(refs(backend.storage(), name))
                .containsEntry(MAIN, staged)
                .containsEntry(SNAPSHOT_REF_PREFIX + staged.name(), staged)
                .doesNotContainKey(STAGING + staged.name());
        try (Repository published = backend.storage().published(name);
                RevWalk walk = new RevWalk(published)) {
            assertThat(walk.parseCommit(staged).getFullMessage())
                    .isEqualTo("objects transferred, transition not yet made");
        }
    }

    /**
     * A staging reference whose commit a live snapshot row names is never eligible, however long it
     * has been there. A publication in flight always has such a row — approval commits it before it
     * publishes — so this is the condition that protects the ordinary case, with the bound behind it
     * for the case the row has not been read yet.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0168"})
    void aStagingReferenceALiveSnapshotRowNamesSurvivesHoweverOldItIs(Backend backend) throws IOException {
        String name = uniqueName("claimed");
        ObjectId claimed;
        try (Repository published = backend.storage().published(name)) {
            claimed = commit(published, "a snapshot the database still knows");
            setRef(published, STAGING + claimed.name(), claimed);
        }
        Marketplace marketplace = marketplaceRepository.register(name, "https://example.invalid/" + name);
        snapshotRepository.create(marketplace.id(), claimed.name(), Snapshot.HELD, null, "alice");
        seenLongAgo(name, STAGING + claimed.name());

        backend.retention().sweepStagingRefs("alice");

        assertThat(refs(backend.storage(), name)).containsEntry(STAGING + claimed.name(), claimed);
        assertThat(reachable(backend.storage(), name, claimed)).isTrue();
    }

    /**
     * A published repository with nothing abandoned in it is left exactly as it was — no reference
     * removed, and no garbage collection provoked into touching served content.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0168"})
    void aPublishedRepositoryWithNothingAbandonedIsUntouched(Backend backend) throws IOException {
        String name = uniqueName("intact");
        Map<String, ObjectId> before;
        try (Repository published = backend.storage().published(name)) {
            ObjectId served = commit(published, "served");
            setRef(published, MAIN, served);
            setRef(published, SNAPSHOT_REF_PREFIX + served.name(), served);
            ObjectId older = commit(published, "an earlier approval, still fetchable by name");
            setRef(published, SNAPSHOT_REF_PREFIX + older.name(), older);
        }
        before = refs(backend.storage(), name);

        backend.retention().sweepStagingRefs("alice");

        assertThat(refs(backend.storage(), name)).isEqualTo(before);
        for (ObjectId id : before.values()) {
            assertThat(reachable(backend.storage(), name, id)).isTrue();
        }
    }

    // --- the parts that are not portable, and the wiring ----------------------------------------

    /**
     * On the filesystem backend the sweep's own garbage collection reclaims the objects in the same
     * pass, not merely at some later collection. The object-store backend tombstones its packs and
     * deletes them after a grace period instead, which is why the portable assertion above is
     * unreachability rather than absence.
     */
    @Test
    @SVCs({"SVC_GW_0168"})
    void onTheFilesystemTheSweptObjectsAreReclaimedByTheSamePass() throws IOException {
        String name = uniqueName("reclaimed");
        ObjectId abandoned;
        try (Repository published = storage.published(name)) {
            abandoned = commit(published, "staged and never published");
            setRef(published, STAGING + abandoned.name(), abandoned);
        }
        seenLongAgo(name, STAGING + abandoned.name());

        retentionService.sweepStagingRefs("alice");

        try (Repository published = storage.published(name)) {
            assertThat(published.getObjectDatabase().has(abandoned)).isFalse();
        }
    }

    /**
     * The wiring, end to end: an on-demand compaction pass runs the sweep, records it on the
     * ledger, and leaves an approved snapshot clonable through the facade — which is the only
     * assertion that covers the whole of "approved content untouched", advertisement included.
     */
    @Test
    @SVCs({"SVC_GW_0168"})
    void compactionRunsTheSweepAndLeavesApprovedContentServed() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("swept");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot approved = approve(registered.snapshot().id());

        ObjectId abandoned;
        try (Repository published = storage.published(name)) {
            abandoned = commit(published, "staged and never published");
            setRef(published, STAGING + abandoned.name(), abandoned);
        }
        seenLongAgo(name, STAGING + abandoned.name());

        retentionService.compact("alice");

        assertThat(refs(storage, name))
                .doesNotContainKey(STAGING + abandoned.name())
                .containsKey(MAIN)
                .containsKey(SNAPSHOT_REF_PREFIX + approved.sha());
        assertThat(fetchLogRepository.list().stream()
                        .filter(entry -> name.equals(entry.get("marketplace")))
                        .map(entry -> (String) entry.get("event")))
                .contains("staging-refs-swept:count=1");
        Path clone = newWorkDir("swept-clone");
        assertThat(gitClone(facadeUrl(name, newPat()), clone.resolve("repo")).exitCode())
                .isZero();
    }

    /**
     * Zero switches the sweep off rather than making every staging reference instantly eligible —
     * the fail-safe reading, because the mis-typed value must not be the one that deletes.
     */
    @Test
    @SVCs({"SVC_GW_0168"})
    void aZeroBoundSwitchesTheSweepOffRatherThanSweepingEverything() throws IOException {
        String name = uniqueName("disabled");
        ObjectId abandoned;
        try (Repository published = storage.published(name)) {
            abandoned = commit(published, "staged and never published");
            setRef(published, STAGING + abandoned.name(), abandoned);
        }
        seenLongAgo(name, STAGING + abandoned.name());

        RetentionService off = retentionOver(storage, withStagingBound(Duration.ZERO));
        RetentionService.PassResult result = off.sweepStagingRefs("alice");

        assertThat(result.acted()).isZero();
        assertThat(refs(storage, name)).containsEntry(STAGING + abandoned.name(), abandoned);
    }

    // --- fixture -------------------------------------------------------------------------------

    /** What a pass three days ago would have written, which is how a reference gets an age. */
    private void seenLongAgo(String marketplace, String ref) {
        sightings.observe(marketplace, List.of(ref), Instant.now().minus(LONG_AGO));
    }

    /** Every reference of a published repository and what it points at, as one comparable value. */
    private static Map<String, ObjectId> refs(GitStorage storage, String marketplace) throws IOException {
        try (Repository published = storage.published(marketplace)) {
            Map<String, ObjectId> byName = new java.util.TreeMap<>();
            for (Ref ref : published.getRefDatabase().getRefs()) {
                if (ref.getObjectId() != null) {
                    byName.put(ref.getName(), ref.getObjectId().copy());
                }
            }
            return byName;
        }
    }

    /** Whether any reference of the published repository still reaches {@code id}. */
    private static boolean reachable(GitStorage storage, String marketplace, ObjectId id) throws IOException {
        try (Repository published = storage.published(marketplace);
                RevWalk walk = new RevWalk(published)) {
            List<ObjectId> tips = new ArrayList<>();
            for (Ref ref : published.getRefDatabase().getRefs()) {
                if (ref.getObjectId() != null) {
                    tips.add(ref.getObjectId().copy());
                }
            }
            for (ObjectId tip : tips) {
                walk.reset();
                try {
                    walk.markStart(walk.parseCommit(tip));
                } catch (IOException missing) {
                    continue;
                }
                for (org.eclipse.jgit.revwalk.RevCommit seen : walk) {
                    if (seen.getId().equals(id)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** The context's settings with one knob moved; nothing else in them reaches the sweep. */
    private SkillsGatewayProperties withStagingBound(Duration bound) {
        SkillsGatewayProperties.Retention retention =
                new SkillsGatewayProperties.Retention(null, null, null, null, bound, null, null);
        return new SkillsGatewayProperties(
                properties.dataDir(),
                null,
                null,
                null,
                null,
                retention,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** A distinct, real commit per message, so a reference points at an object that is really there. */
    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = inserter.insert(new TreeFormatter());
            PersonIdent who = new PersonIdent("Sweep", "sweep@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
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
        RefUpdate.Result result = update.forceUpdate();
        assertThat(result)
                .as("fixture setup must not be the thing that fails")
                .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
    }
}
