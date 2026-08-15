package io.github.jimisola.skillsgateway.storage;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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

    private final Path quarantineDir;
    private final Path publishedDir;

    public FilesystemGitStorage(SkillsGatewayProperties properties) throws IOException {
        this.quarantineDir = properties.dataDir().resolve("quarantine");
        this.publishedDir = properties.dataDir().resolve("published");
        Files.createDirectories(quarantineDir);
        Files.createDirectories(publishedDir);
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
     */
    private static void deleteRef(Repository repository, String ref) throws IOException {
        if (repository.exactRef(ref) == null) {
            return;
        }
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        update.delete();
    }

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
