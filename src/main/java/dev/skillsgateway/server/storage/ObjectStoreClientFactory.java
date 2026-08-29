package dev.skillsgateway.server.storage;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.storage.objectstore.ObjectStoreClient;
import dev.skillsgateway.server.storage.objectstore.S3ObjectStoreClient;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the S3 client the object-store backend talks through.
 *
 * <p>Two details here are deliberate rather than incidental.
 *
 * <p><b>The connection pool is given an explicit lifetime.</b> A long-lived client against a store
 * that closes idle connections will, on the first request after a quiet period, fail in a way that
 * looks exactly like a storage fault — this was observed for real while probing a store during
 * this change's spike, and it cost a wrong diagnosis before it cost a fix. So the pool's idle time
 * is bounded below the store's own, and total connection life is bounded too.
 *
 * <p><b>The credential mechanism is named, not discovered.</b> The first deployment target has no
 * instance metadata service, so anything that leans on the default chain's instance-profile leg
 * does not run there and fails as a timeout in the middle of an approval. Naming
 * {@code web-identity} makes a misconfigured federation a startup error instead.
 */
final class ObjectStoreClientFactory {

    private ObjectStoreClientFactory() {}

    static ObjectStoreClient create(SkillsGatewayProperties.ObjectStore settings) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(settings.region()))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionMaxIdleTime(settings.connectionMaxIdleTime())
                        .connectionTimeToLive(settings.connectionTimeToLive()))
                .credentialsProvider(credentials(settings.credentials()));
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            // A named endpoint is either an S3-compatible store or an S3 VPC endpoint; both want
            // path-style addressing, because neither has per-bucket DNS.
            builder = builder.endpointOverride(URI.create(settings.endpoint())).forcePathStyle(true);
        }
        return new S3ObjectStoreClient(builder.build(), settings.bucket());
    }

    private static software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials(
            SkillsGatewayProperties.Credentials credentials) {
        return switch (credentials.mode()) {
            case DEFAULT -> DefaultCredentialsProvider.create();
            case WEB_IDENTITY -> webIdentity(credentials);
            case STATIC ->
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey()));
        };
    }

    private static software.amazon.awssdk.auth.credentials.AwsCredentialsProvider webIdentity(
            SkillsGatewayProperties.Credentials credentials) {
        WebIdentityTokenFileCredentialsProvider.Builder builder = WebIdentityTokenFileCredentialsProvider.builder();
        if (credentials.roleArn() != null && !credentials.roleArn().isBlank()) {
            builder = builder.roleArn(credentials.roleArn());
        }
        if (credentials.tokenFile() != null && !credentials.tokenFile().isBlank()) {
            builder = builder.webIdentityTokenFile(java.nio.file.Path.of(credentials.tokenFile()));
        }
        return builder.build();
    }
}
