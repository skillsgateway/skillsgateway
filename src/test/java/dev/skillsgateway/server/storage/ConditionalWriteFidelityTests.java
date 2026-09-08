package dev.skillsgateway.server.storage;

import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs {@link ConditionalWriteFidelitySuite} against the store every build develops and tests
 * against, so nothing downstream trusts a conditional write that was never checked.
 *
 * <p>The store is Floci, started by the <b>Arconia Floci dev service</b> and reached through the
 * {@link S3Client} Spring Cloud AWS auto-configures from the connection details that dev service
 * publishes — endpoint, region, credentials and path-style addressing all wired without this test
 * naming an image, a port or a URL. That is the point of the project's dev-services rule: the same
 * container serves {@code bootRun} and the test suite, so the thing developed against and the
 * thing tested against cannot drift apart.
 *
 * <p>The dev service activates only when Spring Cloud AWS is on the classpath, and the artifact
 * that literally satisfies its {@code @ConditionalOnClass} is not sufficient on its own — see the
 * dependency comment in {@code pom.xml} and decision 9 of the {@code pluggable-git-storage}
 * design. Reported upstream as arconia-io/arconia#281.
 *
 * <p>The container is thrown away wholesale at the end of the run, so this subclass does not clean
 * up after itself and does not scope its keys under a prefix. {@link
 * RealS3ConditionalWriteFidelityTests} does both, because a real bucket may not be exclusively
 * ours.
 *
 * <p>This is the fidelity spike from task 2.1 of the {@code pluggable-git-storage} change. It
 * carries no {@code @SVCs} annotation: it verifies the store, not the gateway.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // Dummy OIDC registration with explicit provider details: no discovery at startup.
            "spring.security.oauth2.client.registration.idp.client-id=test",
            "spring.security.oauth2.client.registration.idp.client-secret=test",
            "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/idp",
            "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
            "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
            "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
            "skills-gateway.data-dir=target/test-git-data",
            // Authorization is always enforced and a gateway with no administrator refuses to start
            // (GW_0139). This suite boots the application to reach the store rather than the web
            // surface, so it names one and never uses it.
            "skills-gateway.roles.admins=fidelity-suite",
            // Every background pass off: this test exercises the object store, not the gateway.
            "skills-gateway.webhooks.enabled=false",
            "skills-gateway.audit-export.enabled=false",
            "skills-gateway.retention.enabled=false",
            "skills-gateway.vetting.revet.enabled=false",
            "skills-gateway.sync.enabled=false"
        })
@DisplayName("Object-store conditional-write fidelity — Floci (Arconia dev service)")
class ConditionalWriteFidelityTests extends ConditionalWriteFidelitySuite {

    private static final String BUCKET = "fidelity";

    @Autowired
    private S3Client s3;

    @Override
    protected S3Client s3() {
        return s3;
    }

    @Override
    protected String bucket() {
        return BUCKET;
    }

    @Override
    protected void prepareStore() {
        // The dev service hands over a running store, not a provisioned one, and the container is
        // shared by every test in this context, so bucket creation has to tolerate one that is
        // already there rather than assume a clean store.
        if (s3.listBuckets().buckets().stream().noneMatch(b -> BUCKET.equals(b.name()))) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
    }
}
