package io.github.jimisola.skillsgateway.storage;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.springframework.stereotype.Component;

@Component
public class FilesystemGitStorage implements GitStorage {

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
        if (repository.resolve(Constants.R_HEADS + "main") == null) {
            repository.close();
            return Optional.empty();
        }
        return Optional.of(repository);
    }

    private static Repository openOrCreate(Path path) throws IOException {
        Repository repository = open(path);
        if (!repository.getObjectDatabase().exists()) {
            repository.create(true);
        }
        return repository;
    }

    private static Repository open(Path path) throws IOException {
        return new RepositoryBuilder().setGitDir(path.toFile()).setBare().build();
    }
}
