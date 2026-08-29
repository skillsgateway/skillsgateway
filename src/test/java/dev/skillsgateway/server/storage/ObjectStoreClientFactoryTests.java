package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreClient;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreTestSupport;
import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The client the backend talks through, including the one setting that was learned the hard way.
 *
 * <p>A long-lived S3 client against a store that closes idle connections fails on the first request
 * after a quiet period, and it fails as a transport error that reads exactly like a storage fault —
 * during this change's own store probe that cost a wrong diagnosis before it cost a fix. So the
 * pool is given an explicit idle bound below any plausible store's, and a total connection lifetime
 * above it; both are settings rather than assumptions, because "below the store's idle timeout" is
 * a fact about the store and not about us.
 */
class ObjectStoreClientFactoryTests {

    // the connection pool is given an explicit, ordered lifetime by default
    @Test
    @SVCs({"SVC_GW_0111"})
    void theConnectionPoolHasAnExplicitLifetime() {
        SkillsGatewayProperties.ObjectStore settings =
                new SkillsGatewayProperties.ObjectStore(null, "eu-north-1", "skills", null, null, null, null, null);

        assertThat(settings.connectionMaxIdleTime())
                .as("an unset idle bound is an unbounded one, which is the failure this setting exists to stop")
                .isNotNull()
                .isPositive();
        assertThat(settings.connectionTimeToLive()).isNotNull().isPositive();
        assertThat(settings.connectionMaxIdleTime())
                .as("a connection must be retired for being idle before it is retired for being old")
                .isLessThan(settings.connectionTimeToLive());
    }

    // a client built from configuration alone can round-trip an object
    @Test
    @SVCs({"SVC_GW_0111"})
    void aClientBuiltFromConfigurationWorks() throws IOException {
        AwsConnectionDetails connection = ObjectStoreTestSupport.connection();
        String bucket = ObjectStoreTestSupport.bucket(ObjectStoreTestSupport.BUCKET);
        SkillsGatewayProperties.Credentials credentials = new SkillsGatewayProperties.Credentials(
                SkillsGatewayProperties.Credentials.Mode.STATIC,
                connection.getAccessKey(),
                connection.getSecretKey(),
                null,
                null);
        SkillsGatewayProperties.ObjectStore settings = new SkillsGatewayProperties.ObjectStore(
                connection.getEndpoint().toString(),
                connection.getRegion(),
                bucket,
                null,
                credentials,
                null,
                null,
                null);

        ObjectStoreClient client = ObjectStoreClientFactory.create(settings);
        String key = "factory-" + UUID.randomUUID();
        byte[] body = "round trip".getBytes(StandardCharsets.UTF_8);

        client.put(key, body);
        try {
            assertThat(client.get(key))
                    .hasValueSatisfying(stored -> assertThat(stored.body()).isEqualTo(body));
            assertThat(client.putIfMatch(key, body, "\"not-the-current-etag\""))
                    .as("the client this factory builds must carry conditional writes through faithfully")
                    .isEmpty();
        } finally {
            client.delete(key);
        }
    }
}
