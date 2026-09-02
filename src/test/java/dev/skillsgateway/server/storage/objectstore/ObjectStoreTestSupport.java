package dev.skillsgateway.server.storage.objectstore;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.arconia.dev.services.core.autoconfigure.DevServicesAutoConfiguration;
import io.arconia.dev.services.floci.FlociDevServicesAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One Floci container, started by the Arconia dev service, for every object-store test in this JVM.
 *
 * <p>The project's rule is that a container-backed test uses a dev service wherever one exists, so
 * that one container serves both {@code bootRun} and the suite and the thing developed against
 * cannot drift from the thing tested against. The dev service is a Spring auto-configuration, so
 * reaching it means a context — but only the four auto-configurations that wire an
 * {@link S3Client} from the connection details the dev service publishes, not the gateway. The
 * store is not the gateway, and a suite that had to start the gateway to test the store would be
 * testing both.
 */
public final class ObjectStoreTestSupport {

    private ObjectStoreTestSupport() {}

    private static final class Holder {
        private static final ConfigurableApplicationContext CONTEXT = new SpringApplicationBuilder(Wiring.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        DevServicesAutoConfiguration.class,
        ServiceConnectionAutoConfiguration.class,
        FlociDevServicesAutoConfiguration.class,
        RegionProviderAutoConfiguration.class,
        CredentialsProviderAutoConfiguration.class,
        AwsAutoConfiguration.class,
        S3AutoConfiguration.class
    })
    static class Wiring {}

    /** The S3 client the dev service wired: endpoint, credentials and path-style addressing. */
    public static S3Client s3() {
        return Holder.CONTEXT.getBean(S3Client.class);
    }

    /** The bucket every object-store suite shares. */
    public static final String BUCKET = "skills-gateway";

    /** Where the dev service's store actually is, for the few tests that build their own client. */
    public static io.awspring.cloud.autoconfigure.core.AwsConnectionDetails connection() {
        return Holder.CONTEXT.getBean(io.awspring.cloud.autoconfigure.core.AwsConnectionDetails.class);
    }

    /** An {@link ObjectStoreClient} on the shared bucket, ready to be decorated. */
    public static S3ObjectStoreClient client() {
        return new S3ObjectStoreClient(s3(), bucket(BUCKET));
    }

    /** A key prefix nothing else in the run will touch, so suites cannot see each other's state. */
    public static String isolatedPrefix(String name) {
        return name + "-" + java.util.UUID.randomUUID() + "/";
    }

    /**
     * Properties selecting the object-store backend on the shared bucket under {@code prefix},
     * with a local cache of its own so one suite's cache cannot answer another's read.
     */
    public static SkillsGatewayProperties properties(String prefix, Duration refFreshness, Duration packGrace)
            throws IOException {
        Path data = Files.createTempDirectory(Files.createDirectories(Path.of("target", "object-store")), "backend-");
        SkillsGatewayProperties.Cache cache =
                new SkillsGatewayProperties.Cache(data.resolve("cache"), null, null, null, refFreshness, packGrace);
        SkillsGatewayProperties.ObjectStore objectStore =
                new SkillsGatewayProperties.ObjectStore(null, "eu-north-1", BUCKET, prefix, null, cache, null, null);
        SkillsGatewayProperties.Storage storage = new SkillsGatewayProperties.Storage(
                SkillsGatewayProperties.Storage.Backend.OBJECT_STORE, objectStore, null);
        return new SkillsGatewayProperties(
                data, null, null, null, null, null, null, null, null, null, null, null, null, null, storage, null);
    }

    /** A backend on the shared bucket, under its own prefix, reading through {@code client}. */
    public static ObjectStoreGitStorage storage(ObjectStoreClient client, String prefix) throws IOException {
        return storage(client, prefix, Duration.ZERO, Duration.ofHours(1));
    }

    /** As {@link #storage(ObjectStoreClient, String)}, with the two bounds stated explicitly. */
    public static ObjectStoreGitStorage storage(
            ObjectStoreClient client, String prefix, Duration refFreshness, Duration packGrace) throws IOException {
        return new ObjectStoreGitStorage(
                client, properties(prefix, refFreshness, packGrace), new ObjectStoreStatistics());
    }

    /** A bucket that exists, created once and shared; the store is handed over running, not set up. */
    public static String bucket(String name) {
        S3Client s3 = s3();
        if (s3.listBuckets().buckets().stream().noneMatch(b -> name.equals(b.name()))) {
            s3.createBucket(b -> b.bucket(name));
        }
        return name;
    }
}
