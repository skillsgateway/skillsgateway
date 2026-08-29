package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreClient;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreGitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreStatistics;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the one {@link GitStorage} the whole gateway uses, from the name the operator gave.
 *
 * <p>Named, never inferred, and with no fallback in either direction. An absent selection is the
 * filesystem, which is what every existing deployment already has, so an upgrade changes nothing.
 * An unrecognised name fails startup through the enum binding. And an {@code object-store}
 * selection that cannot be completed — no bucket, no region, a credential mode missing what it
 * needs — fails startup naming what is missing, rather than degrading to a filesystem nobody asked
 * for. A gateway that quietly serves from local disk while the operator believes it is serving
 * from a bucket is the same defect as a volume that silently loses published content: a system
 * reporting healthy while serving from storage nobody chose.
 *
 * <p>The bucket is then probed before the first request is served. Conditional writes are the one
 * exotic primitive this design needs and there is no degraded mode without them, so a store that
 * cannot serialize reference transitions has to be a failed start rather than a corruption
 * discovered during an approval.
 */
@Configuration
public class GitStorageConfiguration {

    /** Counters the object-store backend keeps about its own contention and caching. */
    @Bean
    public ObjectStoreStatistics objectStoreStatistics() {
        return new ObjectStoreStatistics();
    }

    @Bean
    @Requirements({"GW_0111"})
    public GitStorage gitStorage(
            SkillsGatewayProperties properties,
            ObjectStoreStatistics statistics,
            ObjectProvider<ObjectStoreClient> clients)
            throws IOException {
        return switch (properties.storage().backend()) {
            case FILESYSTEM -> new FilesystemGitStorage(properties);
            case OBJECT_STORE -> objectStore(properties, statistics, clients);
        };
    }

    private GitStorage objectStore(
            SkillsGatewayProperties properties,
            ObjectStoreStatistics statistics,
            ObjectProvider<ObjectStoreClient> clients)
            throws IOException {
        SkillsGatewayProperties.ObjectStore settings = properties.storage().objectStore();
        require(settings.bucket(), "skills-gateway.storage.object-store.bucket");
        require(settings.region(), "skills-gateway.storage.object-store.region");
        // DEFAULT is the SDK's own chain and has nothing for the gateway to insist on; the other
        // two modes name what they need, and a mode that cannot be honoured is a failed start.
        SkillsGatewayProperties.Credentials credentials = settings.credentials();
        if (credentials.mode() == SkillsGatewayProperties.Credentials.Mode.STATIC) {
            require(credentials.accessKeyId(), "skills-gateway.storage.object-store.credentials.access-key-id");
            require(credentials.secretAccessKey(), "skills-gateway.storage.object-store.credentials.secret-access-key");
        } else if (credentials.mode() == SkillsGatewayProperties.Credentials.Mode.WEB_IDENTITY) {
            requireWebIdentity(credentials);
        }
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig()
                .setBlockSize(settings.cache().blockSizeBytes())
                .setBlockLimit(settings.cache().blockCacheBytes()));
        ObjectStoreClient client = clients.getIfAvailable(() -> ObjectStoreClientFactory.create(settings));
        ObjectStoreGitStorage storage = new ObjectStoreGitStorage(client, properties, statistics);
        storage.probe();
        return storage;
    }

    /**
     * Web identity needs a role and a token, and both have a standard environment spelling because
     * that is what a service-account annotation projects into the pod. Either source will do; not
     * having one at all must not become a walk down the default chain to a metadata service the
     * deployment does not have.
     */
    private static void requireWebIdentity(SkillsGatewayProperties.Credentials credentials) {
        boolean role = notBlank(credentials.roleArn()) || notBlank(System.getenv("AWS_ROLE_ARN"));
        boolean token = notBlank(credentials.tokenFile()) || notBlank(System.getenv("AWS_WEB_IDENTITY_TOKEN_FILE"));
        if (!role || !token) {
            throw new IllegalStateException(
                    ("skills-gateway.storage.backend is object-store with credentials.mode=web-identity, but the role"
                                    + " and token were not resolvable: set"
                                    + " skills-gateway.storage.object-store.credentials.role-arn and .token-file, or"
                                    + " provide AWS_ROLE_ARN and AWS_WEB_IDENTITY_TOKEN_FILE (which is what an"
                                    + " annotated service account projects). The gateway refuses to start rather than"
                                    + " fall back to a credential chain this deployment has no metadata service for.")
                            .strip());
        }
    }

    private static void require(String value, String property) {
        if (!notBlank(value)) {
            throw new IllegalStateException(
                    "skills-gateway.storage.backend is object-store, so %s must be set; the gateway refuses to start"
                                    .formatted(property)
                            + " rather than fall back to a filesystem the operator did not choose");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
