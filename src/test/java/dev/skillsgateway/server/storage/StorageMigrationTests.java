package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
 * The migration between storage backends: what it copies, what it proves, and what it refuses.
 *
 * <p>A migration is the one operation in this change that touches every repository at once, so the
 * property that matters is not that it finishes but that it can tell whether it succeeded. These
 * cases hold it to that: an unverifiable destination is a failure with the repository named, and
 * the source is still exactly what it was afterwards — which is the whole rollback story, since an
 * operator who cannot trust the old volume has nothing to roll back to.
 *
 * <p>Both directions run against a real object store (Floci through the Arconia dev service) rather
 * than against two filesystems, because the interesting half of the copy is the one that has to
 * become packs and a manifest in a bucket.
 */
class StorageMigrationTests {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String SNAPSHOT_PREFIX = "refs/snapshots/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Test
    @SVCs({"SVC_GW_0114"})
    void everyRepositoryInEveryRoleIsCopiedAndVerified() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        Map<GitStorage.Role, String> seeded = seed(source);
        GitStorage target = objectStore();

        StorageMigration.Report report = migration().migrate(source, target);

        assertThat(report.verified())
                .as("a copy the migration itself could not verify must not report success: %s", refusalOf(report))
                .isTrue();
        assertThat(report.repositories()).hasSize(3);
        for (GitStorage.Role role : GitStorage.Role.values()) {
            String marketplace = seeded.get(role);
            assertThat(target.marketplaces(role))
                    .as("%s must hold the marketplace the source held", role)
                    .contains(marketplace);
            assertThat(refs(target, role, marketplace))
                    .as("%s/%s must resolve to exactly what the source resolves to", role, marketplace)
                    .isEqualTo(refs(source, role, marketplace));
            assertThat(head(target, role, marketplace))
                    .as("the head reference must still name the branch the gateway publishes on")
                    .isEqualTo(MAIN);
        }
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void theServedTipAndThePinnedSnapshotBothSurviveTheCopy() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        Map<GitStorage.Role, String> seeded = seed(source);
        GitStorage target = objectStore();

        migration().migrate(source, target);

        String marketplace = seeded.get(GitStorage.Role.PUBLISHED);
        Map<String, String> published = refs(target, GitStorage.Role.PUBLISHED, marketplace);
        // Publication is two references and revocation removes both, so a migration that carried
        // the tip and dropped the pinned reference would produce a marketplace that serves but
        // whose approved snapshot is no longer fetchable by name.
        assertThat(published).containsKey(MAIN);
        assertThat(published.keySet().stream().filter(name -> name.startsWith(SNAPSHOT_PREFIX)))
                .as("the pinned snapshot reference is advertised in its own right and must move too")
                .hasSize(1);
        assertThat(published.get(MAIN))
                .isEqualTo(published.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(SNAPSHOT_PREFIX))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void aHeadPointingSomewhereOtherThanTheDefaultIsCarriedAcross() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        String marketplace = "migration-head-" + COUNTER.incrementAndGet();
        String trunk = Constants.R_HEADS + "trunk";
        try (Repository repository = source.published(marketplace)) {
            ObjectId tip = commit(repository, "on trunk");
            setRef(repository, trunk, tip);
            assertThat(repository.updateRef(Constants.HEAD).link(trunk))
                    .isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
        }
        GitStorage target = objectStore();

        StorageMigration.Report report = migration().migrate(source, target);

        // Both backends link HEAD to main when they create a repository, so a migration that never
        // copied HEAD at all would look right on every repository the gateway itself made. This is
        // the case that can tell the difference: the head has been moved, and it has to move too.
        assertThat(report.verified()).as("%s", refusalOf(report)).isTrue();
        assertThat(head(target, GitStorage.Role.PUBLISHED, marketplace)).isEqualTo(trunk);
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void aDestinationMissingAReferenceIsRefusedAndTheRepositoryNamed() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        Map<GitStorage.Role, String> seeded = seed(source);
        String damaged = seeded.get(GitStorage.Role.PUBLISHED);
        // Deleted after the copy wrote it and before the verification pass reads it, which is the
        // shape of every way a copy can be wrong: the bytes went somewhere and are not there now.
        GitStorage target = new DamagingGitStorage(objectStore(), GitStorage.Role.PUBLISHED, damaged, repository -> {
            RefUpdate update = repository.updateRef(MAIN);
            update.setForceUpdate(true);
            update.delete();
        });

        StorageMigration.Report report = migration().migrate(source, target);

        assertThat(report.verified())
                .as("a destination that lost a reference must not be reported as migrated")
                .isFalse();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().getFirst().marketplace()).isEqualTo(damaged);
        assertThat(report.failures().getFirst().role()).isEqualTo(GitStorage.Role.PUBLISHED);
        assertThat(report.refusal())
                .as("the refusal must name the repository and say the source is still intact")
                .contains(damaged)
                .contains(MAIN)
                .contains("source backend is untouched");
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void aDestinationWhoseHeadNamesAnotherBranchIsRefused() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        Map<GitStorage.Role, String> seeded = seed(source);
        String damaged = seeded.get(GitStorage.Role.PUBLISHED);
        // The head is the branch a client checks out on clone, so a destination whose head names
        // something else serves a different marketplace than the source did -- with every object
        // and every reference present, which is exactly the failure a byte count would miss.
        GitStorage target =
                new DamagingGitStorage(objectStore(), GitStorage.Role.PUBLISHED, damaged, repository -> repository
                        .updateRef(Constants.HEAD, true)
                        .link(Constants.R_HEADS + "elsewhere"));

        StorageMigration.Report report = migration().migrate(source, target);

        assertThat(report.verified()).isFalse();
        assertThat(report.refusal())
                .contains("HEAD is linked to")
                .contains("elsewhere")
                .contains(MAIN);
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void theSourceIsLeftByteIdentical() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        seed(source);
        Map<String, String> before = digest(root);

        StorageMigration.Report report = migration().migrate(source, objectStore());

        assertThat(report.verified()).isTrue();
        // Not "the references still resolve the same": every byte, because the volume is the
        // rollback and an operator has to be able to discard the destination instead of the source.
        assertThat(digest(root))
                .as("the migration reads the source and writes nothing to it")
                .isEqualTo(before);
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void theReverseDirectionRestoresThePreviousState() throws IOException {
        Path root = filesystemRoot();
        GitStorage source = filesystem(root);
        Map<GitStorage.Role, String> seeded = seed(source);
        GitStorage bucket = objectStore();
        assertThat(migration().migrate(source, bucket).verified()).isTrue();

        Path restored = filesystemRoot();
        StorageMigration.Report back = migration().migrate(bucket, filesystem(restored));

        assertThat(back.verified())
                .as("switching the property back has to be a real rollback, not a hope: %s", refusalOf(back))
                .isTrue();
        GitStorage rolledBack = filesystem(restored);
        for (GitStorage.Role role : GitStorage.Role.values()) {
            String marketplace = seeded.get(role);
            assertThat(refs(rolledBack, role, marketplace))
                    .as("%s/%s after the round trip", role, marketplace)
                    .isEqualTo(refs(source, role, marketplace));
        }
    }

    @Test
    @SVCs({"SVC_GW_0114"})
    void aRepositoryTheSourceDoesNotHoldIsNotInventedAtTheDestination() throws IOException {
        GitStorage source = filesystem(filesystemRoot());
        source.quarantine("only-quarantined").close();
        GitStorage target = objectStore();

        StorageMigration.Report report = migration().migrate(source, target);

        assertThat(report.verified()).isTrue();
        assertThat(target.marketplaces(GitStorage.Role.QUARANTINE)).containsExactly("only-quarantined");
        assertThat(target.marketplaces(GitStorage.Role.PUBLISHED))
                .as("a marketplace with nothing published must not gain an empty published repository")
                .isEmpty();
        assertThat(target.marketplaces(GitStorage.Role.HOSTED)).isEmpty();
    }

    // --- fixtures ---------------------------------------------------------------------------

    /** One marketplace per role, each with real objects and the references its role really has. */
    private static Map<GitStorage.Role, String> seed(GitStorage storage) throws IOException {
        Map<GitStorage.Role, String> seeded = new HashMap<>();
        int run = COUNTER.incrementAndGet();
        for (GitStorage.Role role : GitStorage.Role.values()) {
            String marketplace = "migration-" + role.path() + "-" + run;
            seeded.put(role, marketplace);
            try (Repository repository = storage.open(role, marketplace)) {
                ObjectId tip = commit(repository, "content in " + role.path());
                setRef(repository, SNAPSHOT_PREFIX + tip.name(), tip);
                setRef(repository, MAIN, tip);
            }
        }
        return seeded;
    }

    private static StorageMigration migration() throws IOException {
        return new StorageMigration(
                Files.createTempDirectory(Files.createDirectories(Path.of("target", "storage-migration")), "scratch-"));
    }

    private static Path filesystemRoot() throws IOException {
        return Files.createTempDirectory(
                Files.createDirectories(Path.of("target", "storage-migration")), "filesystem-");
    }

    private static GitStorage filesystem(Path root) throws IOException {
        return new FilesystemGitStorage(new SkillsGatewayProperties(
                root, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    private static GitStorage objectStore() throws IOException {
        return ObjectStoreTestSupport.storage(
                ObjectStoreTestSupport.client(), ObjectStoreTestSupport.isolatedPrefix("migration"));
    }

    private static Map<String, String> refs(GitStorage storage, GitStorage.Role role, String marketplace)
            throws IOException {
        Map<String, String> refs = new TreeMap<>();
        try (Repository repository = storage.open(role, marketplace)) {
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                if (ref.getObjectId() != null) {
                    refs.put(ref.getName(), ref.getObjectId().name());
                }
            }
        }
        return refs;
    }

    private static String head(GitStorage storage, GitStorage.Role role, String marketplace) throws IOException {
        try (Repository repository = storage.open(role, marketplace)) {
            Ref head = repository.exactRef(Constants.HEAD);
            return head != null && head.isSymbolic() ? head.getTarget().getName() : null;
        }
    }

    private static String refusalOf(StorageMigration.Report report) {
        return report.verified() ? "verified" : report.refusal();
    }

    /** Every file under a root, by relative path, with its content hash. */
    private static Map<String, String> digest(Path root) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> all = files.filter(Files::isRegularFile).toList();
            for (Path file : all) {
                try {
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    digests.put(
                            root.relativize(file).toString(),
                            HexFormat.of().formatHex(sha.digest(Files.readAllBytes(file))));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return digests;
    }

    private static ObjectId commit(Repository repository, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = inserter.insert(new TreeFormatter());
            PersonIdent who = new PersonIdent("Migration", "migration@example.invalid", Instant.EPOCH, ZoneOffset.UTC);
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

    /**
     * A destination that quietly loses one reference after it has been written — the migration's
     * own verification pass is the only thing standing between that and an operator discarding the
     * source volume. The deletion happens on the second open of the damaged repository, which is
     * the verification read, so the copy genuinely succeeded and the check genuinely has to catch
     * it rather than the write having been refused.
     */
    private record DamagingGitStorage(GitStorage delegate, GitStorage.Role role, String marketplace, Damage damage)
            implements GitStorage {

        /** What is done to the destination repository between the copy and the verification. */
        @FunctionalInterface
        interface Damage {
            void apply(Repository repository) throws IOException;
        }

        private static final Map<String, Integer> OPENS = new HashMap<>();

        @Override
        public Set<String> marketplaces(Role role) throws IOException {
            return delegate.marketplaces(role);
        }

        @Override
        public Repository open(Role role, String marketplace) throws IOException {
            Repository repository = delegate.open(role, marketplace);
            if (role == this.role && marketplace.equals(this.marketplace)) {
                String key = role + "/" + marketplace + "/" + System.identityHashCode(this);
                int opens = OPENS.merge(key, 1, Integer::sum);
                if (opens == 2) {
                    damage.apply(repository);
                }
            }
            return repository;
        }

        @Override
        public Repository quarantine(String marketplace) throws IOException {
            return open(Role.QUARANTINE, marketplace);
        }

        @Override
        public Repository hosted(String marketplace) throws IOException {
            return open(Role.HOSTED, marketplace);
        }

        @Override
        public Optional<Repository> hostedIfPresent(String marketplace) throws IOException {
            return delegate.hostedIfPresent(marketplace);
        }

        @Override
        public Repository published(String marketplace) throws IOException {
            return open(Role.PUBLISHED, marketplace);
        }

        @Override
        public Optional<Repository> publishedIfServing(String marketplace) throws IOException {
            return delegate.publishedIfServing(marketplace);
        }

        @Override
        public boolean unpublish(String marketplace, String sha) throws IOException {
            return delegate.unpublish(marketplace, sha);
        }

        @Override
        public boolean commitPublication(String marketplace, String sha) throws IOException {
            return delegate.commitPublication(marketplace, sha);
        }
    }
}
