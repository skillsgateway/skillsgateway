package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import dev.skillsgateway.server.storage.objectstore.ObstructingObjectStoreClient;
import io.github.reqstool.annotations.SVCs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The contract every {@link GitStorage} implementation is held to, run against each of them.
 *
 * <p>This suite exists so that "there is no observable difference between backends" is something a
 * build checks rather than something a design claims. It is deliberately written against the seam
 * only — it constructs no Spring context, knows nothing about approval, ingestion or the facade,
 * and touches a backend's internals through exactly one narrow hook (see {@link RefObstruction}).
 * A backend that does not pass it is not a backend.
 *
 * <p>Two of its cases are here because a review of design decision 10 of the
 * {@code pluggable-git-storage} change predicted the two ways a second backend would drift without
 * anyone noticing:
 *
 * <ul>
 *   <li><b>Result codes, not only end states.</b> {@code RefUpdate.delete()} answers with a result,
 *       and a backend that discards it turns a refused deletion into a silent success. On the
 *       revocation path that means the ledger records a marketplace as no longer serving while the
 *       revoked commit is still on the wire, so {@link #aRefusedRefTransitionIsRaisedNotSwallowed}
 *       forces the deletion to fail and requires the failure to surface.
 *   <li><b>The advertised {@code atomic} capability.</b> {@code RefDatabase} defaults
 *       {@code performsAtomicTransactions()} to {@code false} and only {@code RefDirectory} (and
 *       the reftable database) return {@code true}; {@code ReceivePack} advertises
 *       {@code CAPABILITY_ATOMIC} from that method alone. So a DFS backend that inherits the
 *       default silently stops offering atomic pushes to publishers. The server-side flag is no
 *       evidence either way — {@code ReceivePack.service()} overwrites it with
 *       {@code setAtomic(isCapabilityEnabled(CAPABILITY_ATOMIC))} — which is why
 *       {@link #theAtomicPushCapabilityIsAdvertised} reads the advertisement off the wire.
 * </ul>
 *
 * <p>Backends arrive as {@link Backend} parameters, so adding one is adding a line to
 * {@link #backends()}. The parameter carries its own {@link RefObstruction} because "make the next
 * deletion of this ref fail" cannot be expressed portably: on a filesystem it is a stale lock file,
 * on an object store it will be a losing conditional write. What the contract fixes is the
 * observable consequence, not how the failure is provoked.
 */
class GitStorageContractTests {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_PREFIX = "refs/snapshots/";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** One backend under test, plus the one hook the contract cannot express portably. */
    record Backend(String name, GitStorage storage, RefObstruction obstruction) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Makes the next attempt to delete {@code ref} from a published repository fail at the
     * backend's own locking or precondition layer, without removing the ref.
     */
    @FunctionalInterface
    interface RefObstruction {
        void obstruct(String marketplace, String ref) throws IOException;
    }

    static List<Backend> backends() {
        // The suite was written against the filesystem backend alone, before the second one
        // existed, so that the contract was a description of current behavior before it became a
        // requirement on new behavior — the only order in which it can be trusted as a description.
        // Adding a backend is adding a line here, and that is the whole acceptance criterion: a
        // backend that does not pass this suite is not a backend.
        return List.of(filesystemBackend(), objectStoreBackend());
    }

    // --- 3.1 the three roles, the emptiness rules, HEAD, and what publication makes appear ------

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void theThreeRolesAreSeparateRepositories(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        try (Repository quarantine = storage.quarantine(marketplace);
                Repository hosted = storage.hosted(marketplace);
                Repository published = storage.published(marketplace)) {

            ObjectId inQuarantine = commit(quarantine, "quarantined");
            setRef(quarantine, SNAPSHOT_PREFIX + inQuarantine.name(), inQuarantine);

            assertThat(published.exactRef(SNAPSHOT_PREFIX + inQuarantine.name()))
                    .as("quarantine is never served: nothing written there may appear in published")
                    .isNull();
            assertThat(hosted.exactRef(SNAPSHOT_PREFIX + inQuarantine.name()))
                    .as("the publisher's origin is a third repository, not a view of quarantine")
                    .isNull();
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void hostedIfPresentIsEmptyUntilTheOriginExists(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        assertThat(storage.hostedIfPresent(marketplace))
                .as("an upstream marketplace has no origin repository, and asking must not create one")
                .isEmpty();

        storage.hosted(marketplace).close();

        try (Repository origin = open(storage.hostedIfPresent(marketplace))) {
            assertThat(origin).isNotNull();
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void publishedIfServingIsEmptyUntilThereIsAServedTip(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        assertThat(storage.publishedIfServing(marketplace))
                .as("nothing approved yet: the facade must find nothing to open")
                .isEmpty();

        try (Repository published = storage.published(marketplace)) {
            assertThat(storage.publishedIfServing(marketplace))
                    .as("an existing but empty published repository is not a serving marketplace")
                    .isEmpty();

            ObjectId tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);

            assertThat(storage.publishedIfServing(marketplace))
                    .as("a pinned snapshot alone is not the served tip; main is what makes it serve")
                    .isEmpty();

            setRef(published, MAIN, tip);
        }

        try (Repository serving = open(storage.publishedIfServing(marketplace))) {
            assertThat(serving.resolve(MAIN)).isNotNull();
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void everyCreatedRepositoryLinksHeadToMain(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        try (Repository quarantine = storage.quarantine(marketplace);
                Repository hosted = storage.hosted(marketplace);
                Repository published = storage.published(marketplace)) {
            for (Repository repository : List.of(quarantine, hosted, published)) {
                Ref head = repository.exactRef(Constants.HEAD);
                assertThat(head)
                        .as("HEAD must exist on a freshly created repository")
                        .isNotNull();
                assertThat(head.isSymbolic())
                        .as("HEAD must be symbolic, or a client cannot check out what it clones")
                        .isTrue();
                assertThat(head.getTarget().getName())
                        .as("the gateway publishes on main; JGit's own default is master")
                        .isEqualTo(MAIN);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void publicationMakesExactlyTheTwoRefsAppear(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        try (Repository published = storage.published(marketplace)) {
            ObjectId tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);

            assertThat(published.getRefDatabase().getRefsByPrefix("refs/").stream()
                            .map(Ref::getName)
                            .toList())
                    .as("publication puts exactly the served tip and the pinned snapshot on the wire")
                    .containsExactlyInAnyOrder(MAIN, SNAPSHOT_PREFIX + tip.name());
        }
    }

    // --- 3.1b publication, the transition that used to live outside this seam -------------------

    /**
     * Publication as a seam operation, on every backend.
     *
     * <p>This case did not exist while publication was performed by {@code ApprovalService} against
     * a raw repository, and its absence hid two defects at once: a discarded {@code RefUpdate}
     * result, and object transfer built from {@code quarantine.getDirectory().getAbsolutePath()} —
     * {@code null} on a DFS repository, so approval on the object-store backend raised
     * {@code NullPointerException} and no test drove it.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0132"})
    void publicationTransfersObjectsAndLandsBothRefs(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId tip;
        try (Repository quarantine = storage.quarantine(marketplace)) {
            tip = commit(quarantine, "ingested, vetted, held");
            setRef(quarantine, SNAPSHOT_PREFIX + tip.name(), tip);
        }

        assertThat(storage.publish(marketplace, tip.name()))
                .as("the first publication is what starts the marketplace serving")
                .isTrue();

        try (Repository published = storage.published(marketplace)) {
            assertThat(published.getObjectDatabase().has(tip))
                    .as("the objects came across without either repository having a path")
                    .isTrue();
            assertThat(published.getRefDatabase().getRefsByPrefix("refs/").stream()
                            .map(Ref::getName)
                            .toList())
                    .as("exactly the served tip and the pinned snapshot; staging is not left behind")
                    .containsExactlyInAnyOrder(MAIN, SNAPSHOT_PREFIX + tip.name());
        }

        assertThat(storage.publish(marketplace, tip.name()))
                .as("republishing the tip it already serves starts nothing")
                .isFalse();
    }

    /**
     * The #149 guarantee at the seam: a refused transition publishes nothing at all — not the served
     * tip, and not the snapshot reference that is advertised in its own right.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0132"})
    void aRefusedPublicationLeavesNothingOnTheWire(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId tip;
        try (Repository quarantine = storage.quarantine(marketplace)) {
            tip = commit(quarantine, "ingested, vetted, held");
            setRef(quarantine, SNAPSHOT_PREFIX + tip.name(), tip);
        }
        // Ensure the published repository exists before it is obstructed, the way a marketplace that
        // has never served yet does.
        storage.published(marketplace).close();
        backend.obstruction().obstruct(marketplace, MAIN);

        assertThatThrownBy(() -> storage.publish(marketplace, tip.name()))
                .as("a refused publication is raised, never reported as done")
                .isInstanceOf(IOException.class);

        try (Repository published = storage.published(marketplace)) {
            // Not "no references at all": the object-store obstruction moves the served tip to a
            // competing object id of its own to simulate a losing compare-and-swap. The guarantee is
            // about *this* snapshot -- nothing the facade advertises may resolve to it.
            assertThat(published.exactRef(SNAPSHOT_PREFIX + tip.name()))
                    .as("the pinned reference is advertised in its own right, so it must not survive")
                    .isNull();
            assertThat(published.getRefDatabase().getRefsByPrefix("refs/").stream()
                            .filter(ref -> !ref.getName().startsWith(GitStorage.STAGING_REF_PREFIX))
                            .filter(ref -> tip.equals(ref.getObjectId()))
                            .map(Ref::getName)
                            .toList())
                    .as("no advertised reference resolves to a snapshot whose publication was refused")
                    .isEmpty();
        }
    }

    // --- 3.2 unpublish ------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void unpublishRemovesBothRefsAndReportsThatItStoppedTheServing(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId tip;
        try (Repository published = storage.published(marketplace)) {
            tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);
        }

        assertThat(storage.unpublish(marketplace, tip.name()))
                .as("removing the ref that was the served tip is what stopped the marketplace serving")
                .isTrue();

        try (Repository published = storage.published(marketplace)) {
            assertThat(published.exactRef(SNAPSHOT_PREFIX + tip.name()))
                    .as("the pinned ref is advertised in its own right, so it must go too")
                    .isNull();
            assertThat(published.exactRef(MAIN)).isNull();
        }
        assertThat(storage.publishedIfServing(marketplace)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void unpublishOfASupersededSnapshotLeavesTheMarketplaceServing(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId superseded;
        ObjectId current;
        try (Repository published = storage.published(marketplace)) {
            superseded = commit(published, "first approval");
            current = commit(published, "second approval");
            setRef(published, SNAPSHOT_PREFIX + superseded.name(), superseded);
            setRef(published, SNAPSHOT_PREFIX + current.name(), current);
            setRef(published, MAIN, current);
        }

        assertThat(storage.unpublish(marketplace, superseded.name()))
                .as("revoking a snapshot a later approval already replaced stopped nothing")
                .isFalse();

        try (Repository published = storage.published(marketplace)) {
            assertThat(published.exactRef(SNAPSHOT_PREFIX + superseded.name()))
                    .as("the revoked snapshot must stop being fetchable by name")
                    .isNull();
            assertThat(published.exactRef(SNAPSHOT_PREFIX + current.name()))
                    .as("nothing else moves")
                    .isNotNull();
            assertThat(published.resolve(MAIN))
                    .as("the marketplace keeps serving what it was serving")
                    .isEqualTo(current);
        }
        assertThat(storage.publishedIfServing(marketplace)).isPresent();
    }

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void unpublishOfAMarketplaceThatNeverServedReportsThatItStoppedNothing(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        assertThat(storage.unpublish(marketplace, ObjectId.zeroId().name()))
                .as("a marketplace with no published repository cannot have stopped serving")
                .isFalse();

        try (Repository published = storage.published(marketplace)) {
            ObjectId orphan = commit(published, "pinned but never the tip");
            setRef(published, SNAPSHOT_PREFIX + orphan.name(), orphan);

            assertThat(storage.unpublish(marketplace, orphan.name()))
                    .as("there was no tip to remove, so this call is not what stopped the serving")
                    .isFalse();
            assertThat(published.getRefDatabase().exactRef(SNAPSHOT_PREFIX + orphan.name()))
                    .as("the pinned ref goes unconditionally all the same")
                    .isNull();
        }
    }

    /**
     * The result-code case. The end state a swallowed failure leaves behind is indistinguishable
     * from "there was nothing to delete", so asserting end states alone cannot catch it; what
     * distinguishes them is whether the backend looked at what its own ref transition answered.
     */
    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void aRefusedRefTransitionIsRaisedNotSwallowed(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId tip;
        try (Repository published = storage.published(marketplace)) {
            tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
            setRef(published, MAIN, tip);
        }

        backend.obstruction().obstruct(marketplace, SNAPSHOT_PREFIX + tip.name());

        assertThatIOException()
                .as("a revocation that could not remove a ref must not report success")
                .isThrownBy(() -> storage.unpublish(marketplace, tip.name()))
                .withMessageContaining(SNAPSHOT_PREFIX + tip.name());

        try (Repository published = storage.published(marketplace)) {
            assertThat(published.exactRef(SNAPSHOT_PREFIX + tip.name()))
                    .as("the refused deletion really did not happen: the failure is not cosmetic")
                    .isNotNull();
        }
    }

    // --- the advertised push capability ---------------------------------------------------------

    @ParameterizedTest
    @MethodSource("backends")
    @SVCs({"SVC_GW_0112"})
    void theAtomicPushCapabilityIsAdvertised(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();

        try (Repository hosted = storage.hosted(marketplace)) {
            ObjectId pushed = commit(hosted, "pushed by a publisher");
            setRef(hosted, MAIN, pushed);

            assertThat(hosted.getRefDatabase().performsAtomicTransactions())
                    .as("the advertisement is derived from this method alone, and RefDatabase defaults it to false")
                    .isTrue();
            assertThat(advertisement(hosted))
                    .as("a publisher must be offered atomic pushes on every backend, or a multi-ref"
                            + " push silently becomes several independent ones")
                    .contains("atomic");
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Reads what a {@code receive-pack} would put on the wire. The server-side flag is set here on
     * purpose and proves nothing: {@code ReceivePack.service()} overwrites it from what the client
     * enabled, so only the advertisement says whether the capability was ever offered.
     */
    private static String advertisement(Repository repository) throws IOException {
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setAtomic(true);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        receivePack.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(new PacketLineOut(wire)));
        return wire.toString(StandardCharsets.UTF_8);
    }

    private static Repository open(Optional<Repository> repository) {
        return repository.orElseThrow(() -> new AssertionError("expected the repository to be present"));
    }

    private static String marketplace() {
        return "contract-" + COUNTER.incrementAndGet();
    }

    /** A distinct, real commit per message, so refs point at objects the repository actually has. */
    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = inserter.insert(new TreeFormatter());
            PersonIdent who = new PersonIdent("Contract", "contract@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
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

    // --- the filesystem backend under test ------------------------------------------------------

    private static Backend filesystemBackend() {
        try {
            Path root = Files.createTempDirectory(
                    Files.createDirectories(Path.of("target", "storage-contract")), "filesystem-");
            // Every other field of the properties record normalises null to its default, so the
            // data directory is the only one this backend reads.
            SkillsGatewayProperties properties = new SkillsGatewayProperties(
                    root, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            return new Backend("filesystem", new FilesystemGitStorage(properties), obstructOnDisk(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A stale lock file is how a ref deletion is refused on {@code RefDirectory}: the update cannot
     * take the lock and answers {@code LOCK_FAILURE} without touching the ref. It is the same
     * situation a second writer produces, reproduced deterministically.
     */
    private static RefObstruction obstructOnDisk(Path root) {
        return (marketplace, ref) -> {
            Path lock = root.resolve("published").resolve(marketplace + ".git").resolve(ref + ".lock");
            Files.createDirectories(lock.getParent());
            Files.writeString(lock, "held by another writer\n");
        };
    }

    // --- the object-storage backend under test --------------------------------------------------

    /**
     * JGit DFS over an S3-compatible bucket, on its own key prefix so it cannot see anything
     * another suite left behind. The store is Floci through the Arconia dev service — a real store
     * whose conditional-write fidelity was proved before any of this code trusted it.
     */
    private static Backend objectStoreBackend() {
        try {
            String prefix = ObjectStoreTestSupport.isolatedPrefix("contract");
            ObstructingObjectStoreClient store = new ObstructingObjectStoreClient(ObjectStoreTestSupport.client());
            GitStorage storage = ObjectStoreTestSupport.storage(store, prefix);
            return new Backend(
                    "object-store",
                    storage,
                    (marketplace, ref) -> store.obstruct(prefix + "published/" + marketplace + "/manifest", ref));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Guards the guard: the obstruction must be able to make a deletion fail at all. */
    @ParameterizedTest
    @MethodSource("backends")
    void theObstructionHookActuallyObstructs(Backend backend) throws IOException {
        String marketplace = marketplace();
        GitStorage storage = backend.storage();
        ObjectId tip;
        try (Repository published = storage.published(marketplace)) {
            tip = commit(published, "approved");
            setRef(published, SNAPSHOT_PREFIX + tip.name(), tip);
        }

        backend.obstruction().obstruct(marketplace, SNAPSHOT_PREFIX + tip.name());

        try (Repository published = storage.published(marketplace)) {
            RefUpdate update = published.updateRef(SNAPSHOT_PREFIX + tip.name());
            update.setForceUpdate(true);
            assertThat(update.delete())
                    .as(
                            "a negative control that cannot make the deletion fail proves nothing about the case that uses it")
                    .isEqualTo(RefUpdate.Result.LOCK_FAILURE);
        }

        assertThatCode(() -> storage.published(marketplace).close()).doesNotThrowAnyException();
    }
}
