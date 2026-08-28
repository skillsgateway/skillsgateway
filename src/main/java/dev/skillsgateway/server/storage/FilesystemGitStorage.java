package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.springframework.stereotype.Component;

@Component
public class FilesystemGitStorage implements GitStorage {

    private static final String MAIN = "main";
    private static final String SNAPSHOT_REF_PREFIX = "refs/snapshots/";

    /** The results a forced deletion reports when the ref is gone afterwards. */
    private static final Set<RefUpdate.Result> DELETED =
            EnumSet.of(RefUpdate.Result.FORCED, RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE);

    private final Path quarantineDir;
    private final Path publishedDir;
    private final Path hostedDir;

    public FilesystemGitStorage(SkillsGatewayProperties properties) throws IOException {
        this.quarantineDir = properties.dataDir().resolve("quarantine");
        this.publishedDir = properties.dataDir().resolve("published");
        this.hostedDir = properties.dataDir().resolve("hosted");
        Files.createDirectories(quarantineDir);
        Files.createDirectories(publishedDir);
        Files.createDirectories(hostedDir);
    }

    @Override
    public Repository hosted(String marketplace) throws IOException {
        return openOrCreate(hostedDir.resolve(marketplace + ".git"));
    }

    @Override
    public Optional<Repository> hostedIfPresent(String marketplace) throws IOException {
        Path path = hostedDir.resolve(marketplace + ".git");
        return Files.isDirectory(path) ? Optional.of(open(path)) : Optional.empty();
    }

    @Override
    public Repository quarantine(String marketplace) throws IOException {
        return openOrCreate(quarantineDir.resolve(marketplace + ".git"));
    }

    @Override
    public Repository published(String marketplace) throws IOException {
        return openOrCreate(publishedDir.resolve(marketplace + ".git"));
    }

    @Override
    public Optional<Repository> publishedIfServing(String marketplace) throws IOException {
        Path path = publishedDir.resolve(marketplace + ".git");
        if (!Files.isDirectory(path)) {
            return Optional.empty();
        }
        Repository repository = open(path);
        if (repository.resolve(Constants.R_HEADS + MAIN) == null) {
            repository.close();
            return Optional.empty();
        }
        return Optional.of(repository);
    }

    @Override
    @Requirements({"GW_0112"})
    public boolean unpublish(String marketplace, String sha) throws IOException {
        Path path = publishedDir.resolve(marketplace + ".git");
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Repository repository = open(path)) {
            // The pinned ref goes unconditionally: it is advertised on its own, so an approved
            // snapshot stays fetchable by SHA even when it is not the tip.
            deleteRef(repository, SNAPSHOT_REF_PREFIX + sha);
            ObjectId tip = repository.resolve(Constants.R_HEADS + MAIN);
            if (tip == null || !sha.equals(tip.name())) {
                return false;
            }
            deleteRef(repository, Constants.R_HEADS + MAIN);
            return true;
        }
    }

    /**
     * Deletes a ref if it is there. Force is required because the ref is not being fast-forwarded
     * to anything — the whole point is that nothing replaces it.
     *
     * <p>The result is checked rather than discarded (GW_0112). A ref update can be refused —
     * {@code LOCK_FAILURE} when another writer holds the lock, {@code IO_FAILURE} underneath it —
     * and this is the revocation path, so a refusal that returned quietly would have
     * {@code unpublish} report that a snapshot stopped being served while it is still advertised.
     * Raising is the only honest answer: the caller records revocations on the ledger, and a ledger
     * entry that disagrees with what the facade serves is worse than a failed revocation.
     */
    private static void deleteRef(Repository repository, String ref) throws IOException {
        if (repository.exactRef(ref) == null) {
            return;
        }
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.delete();
        if (!DELETED.contains(result)) {
            throw new IOException("could not delete %s in %s: %s".formatted(ref, repository.getDirectory(), result));
        }
    }

    @Requirements({"GW_0112"})
    private static Repository openOrCreate(Path path) throws IOException {
        Repository repository = open(path);
        if (!repository.getObjectDatabase().exists()) {
            repository.create(true);
            // JGit initializes bare repos with HEAD -> refs/heads/master; the gateway
            // publishes to main, and clients need HEAD to resolve for checkout.
            repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + MAIN);
        }
        return repository;
    }

    private static Repository open(Path path) throws IOException {
        return new RepositoryBuilder().setGitDir(path.toFile()).setBare().build();
    }
}
