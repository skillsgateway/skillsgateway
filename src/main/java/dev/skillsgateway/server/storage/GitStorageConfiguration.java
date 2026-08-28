package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.observability.MeteredObjectStoreClient;
import dev.skillsgateway.server.observability.ObjectStoreMetrics;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreClient;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreGitStorage;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreStatistics;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

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

    /**
     * Turns the backend name into the backend, and turns a name nobody implements into a refusal
     * that says what would have been accepted.
     *
     * <p>Without this the enum binding refuses with "No enum constant ...Backend.magic" — which
     * names the value and the type but not one of the two spellings that would have worked. The
     * accepted set is closed and known at compile time, so leaving the operator to find it in the
     * documentation is a choice, not a constraint.
     */
    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, SkillsGatewayProperties.Storage.Backend> storageBackendConverter() {
        return name -> Arrays.stream(SkillsGatewayProperties.Storage.Backend.values())
                .filter(backend -> canonical(backend.name()).equals(canonical(name)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "skills-gateway.storage.backend is '%s', which is not a backend this gateway implements;"
                                        .formatted(name)
                                + " the accepted values are "
                                + accepted()
                                + ". The gateway refuses to start rather than infer one, because serving from a"
                                + " backend the operator did not name is undetectable from outside."));
    }

    /** The accepted spellings, in the kebab-case an operator actually writes in a property file. */
    private static String accepted() {
        return Arrays.stream(SkillsGatewayProperties.Storage.Backend.values())
                .map(backend -> "'" + backend.name().toLowerCase(Locale.ROOT).replace('_', '-') + "'")
                .collect(Collectors.joining(" and "));
    }

    private static String canonical(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The backend's counters as meters. A {@link org.springframework.boot.actuate.autoconfigure
     * .metrics.MetricsAutoConfiguration MeterBinder} bean is bound to every registry by Spring
     * Boot, so this needs no registry of its own and costs nothing on the filesystem backend,
     * where the counters simply never move.
     */
    @Bean
    public ObjectStoreMetrics objectStoreMetrics(ObjectStoreStatistics statistics) {
        return new ObjectStoreMetrics(statistics);
    }

    @Bean
    @Requirements({"GW_0111"})
    public GitStorage gitStorage(
            SkillsGatewayProperties properties,
            ObjectStoreStatistics statistics,
            ObjectProvider<ObjectStoreClient> clients,
            ObjectProvider<MeterRegistry> meters)
            throws IOException {
        return switch (properties.storage().backend()) {
            case FILESYSTEM -> new FilesystemGitStorage(properties);
            case OBJECT_STORE -> objectStore(properties, statistics, clients, meters);
        };
    }

    private GitStorage objectStore(
            SkillsGatewayProperties properties,
            ObjectStoreStatistics statistics,
            ObjectProvider<ObjectStoreClient> clients,
            ObjectProvider<MeterRegistry> meters)
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
        // Request latency is the store's, not ours, and on the first deployment target the store is
        // a network away; without a timer around the requests there is no way to tell a slow
        // approval from a slow bucket. A context with no registry runs the undecorated client.
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            client = new MeteredObjectStoreClient(client, registry);
        }
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
