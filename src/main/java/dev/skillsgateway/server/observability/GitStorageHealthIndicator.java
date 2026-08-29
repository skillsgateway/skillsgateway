package dev.skillsgateway.server.observability;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreGitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether the storage the gateway was told to use is actually there (GW_0116).
 *
 * <p>The storage is where every byte the gateway serves lives, and it is the one dependency whose
 * absence the gateway cannot report by failing a request in any legible way: a facade fetch
 * against an unreachable bucket looks like a broken repository, not like a broken bucket. So it
 * gets a health indicator, on both backends, saying which one is configured and whether it can be
 * reached right now.
 *
 * <p>Reachability is checked with the cheapest read the backend has and never with a write. A
 * health endpoint is polled, and a probe that wrote would put the gateway's own liveness traffic
 * on the path it is supposed to be observing. The conditional-write probe — the one that decides
 * whether this store can serialize reference transitions at all — is the startup gate instead, and
 * is <em>reported</em> here rather than repeated: it is a property of the bucket, it was proved
 * before the first request was served, and a gateway for which it failed never started.
 */
@Component
public class GitStorageHealthIndicator implements HealthIndicator {

    private final GitStorage storage;
    private final SkillsGatewayProperties properties;

    public GitStorageHealthIndicator(GitStorage storage, SkillsGatewayProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    @Requirements({"GW_0116"})
    public Health health() {
        if (storage instanceof ObjectStoreGitStorage objectStore) {
            return objectStoreHealth(objectStore);
        }
        return filesystemHealth();
    }

    private Health objectStoreHealth(ObjectStoreGitStorage objectStore) {
        SkillsGatewayProperties.ObjectStore settings = properties.storage().objectStore();
        Health.Builder health = Health.up()
                .withDetail("backend", "object-store")
                .withDetail("bucket", settings.bucket())
                .withDetail("region", settings.region())
                .withDetail("conditionalWrites", objectStore.probedAt() == null ? "unproved" : "verified at startup");
        try {
            objectStore.checkReachable();
            return health.build();
        } catch (IOException e) {
            return Health.down(e)
                    .withDetail("backend", "object-store")
                    .withDetail("bucket", settings.bucket())
                    .build();
        }
    }

    private Health filesystemHealth() {
        Path dataDir = properties.dataDir();
        Health.Builder health = Health.up().withDetail("backend", "filesystem").withDetail("dataDir", dataDir);
        if (!Files.isDirectory(dataDir)) {
            return Health.down()
                    .withDetail("backend", "filesystem")
                    .withDetail("dataDir", dataDir)
                    .withDetail("reason", "the data directory is not there")
                    .build();
        }
        if (!Files.isWritable(dataDir)) {
            return Health.down()
                    .withDetail("backend", "filesystem")
                    .withDetail("dataDir", dataDir)
                    .withDetail("reason", "the data directory is not writable")
                    .build();
        }
        return health.build();
    }
}
