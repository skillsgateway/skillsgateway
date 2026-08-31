package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Proves that the object store the gateway develops and tests against implements conditional
 * writes faithfully, before any code trusts it.
 *
 * <p>The whole consistency model of the object-store git backend reduces to one behavior: a
 * conditional {@code PutObject} must fail when its precondition no longer holds. A store that
 * accepts every write regardless of {@code If-Match} would let a <em>broken</em> backend pass the
 * concurrency suite that is supposed to justify the design — green, and meaningless. So the store
 * is tested before it is trusted.
 *
 * <p>Assertion 2 deliberately checks the stored content after the refusal, not just the status
 * code: a store that answers 412 and writes anyway is the worst possible outcome, and it is exactly
 * what a status-only assertion misses. Assertion 4 uses real threads racing off one barrier,
 * because sequential calls cannot distinguish a correct implementation from one that ignores
 * preconditions but happens to be ordered.
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
@DisplayName("Object-store conditional-write fidelity")
class ConditionalWriteFidelityTests {

    private static final String BUCKET = "fidelity";
    private static final String KEY = "manifest";

    private static final byte[] ORIGINAL = "original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTRUDER = "intruder".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private S3Client s3;

    @BeforeEach
    void resetObject() {
        // One @BeforeEach, not two: JUnit does not order lifecycle methods declared at the same
        // level, and a separate bucket-creation hook ran after this one often enough to fail.
        //
        // The dev service hands over a running store, not a provisioned one, and the container is
        // shared by every test in this context, so bucket creation has to tolerate one that is
        // already there rather than assume a clean store.
        if (s3.listBuckets().buckets().stream().noneMatch(b -> BUCKET.equals(b.name()))) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        put(ORIGINAL, null, null);
    }

    /** 1. A conditional PUT presenting the current ETag succeeds, and yields a *new* ETag. */
    @Test
    @DisplayName("1. If-Match with the current ETag succeeds and returns a new ETag")
    void matchingEtagSucceedsAndRotatesTheEtag() {
        String base = currentEtag();

        PutObjectResponse updated = put(REPLACEMENT, base, null);

        assertThat(updated.eTag()).isNotNull();
        assertThat(updated.eTag())
                .as("a successful conditional write must yield a new ETag, or no later write can be conditioned on it")
                .isNotEqualTo(base);
        assertThat(body()).isEqualTo(REPLACEMENT);
        assertThat(currentEtag()).isEqualTo(updated.eTag());
    }

    /**
     * 2. A conditional PUT presenting a stale ETag fails with 412 — and, the part that actually
     * matters, leaves the stored object untouched.
     */
    @Test
    @DisplayName("2. If-Match with a stale ETag returns 412 and does not write")
    void staleEtagIsRefusedAndTheObjectIsUnchanged() {
        String stale = currentEtag();
        put(REPLACEMENT, stale, null); // someone else moves the object on
        String current = currentEtag();

        assertThatThrownBy(() -> put(INTRUDER, stale, null))
                .isInstanceOf(S3Exception.class)
                .satisfies(thrown -> assertThat(((S3Exception) thrown).statusCode())
                        .as("a stale precondition must be refused with 412 Precondition Failed")
                        .isEqualTo(412));

        assertThat(body())
                .as("a refused conditional write must not have written; a 412 that writes anyway is the worst outcome")
                .isEqualTo(REPLACEMENT);
        assertThat(currentEtag()).isEqualTo(current);
    }

    /** 3. {@code If-None-Match: *} is a create-exactly-once primitive. */
    @Test
    @DisplayName("3. If-None-Match: * creates exactly once")
    void createOnceSucceedsOnceAndThenFails() {
        String key = "create-once-" + System.nanoTime();

        PutObjectResponse created = putKey(key, ORIGINAL, null, "*");
        assertThat(created.eTag()).isNotNull();

        assertThatThrownBy(() -> putKey(key, INTRUDER, null, "*"))
                .isInstanceOf(S3Exception.class)
                .satisfies(thrown ->
                        assertThat(((S3Exception) thrown).statusCode()).isEqualTo(412));

        assertThat(bodyOf(key))
                .as("the losing create must not have overwritten the winner")
                .isEqualTo(ORIGINAL);
    }

    /**
     * 4. The property the whole design rests on: from one base ETag, exactly one of N genuinely
     * concurrent writers wins and every other is refused with 412.
     */
    @Test
    @DisplayName("4. Under real concurrency from one base ETag, exactly one writer wins")
    void exactlyOneConcurrentWriterWins() throws Exception {
        int writers = 8;
        String base = currentEtag();

        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Callable<String>> tasks = IntStream.range(0, writers)
                    .<Callable<String>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await(1, TimeUnit.MINUTES);
                        try {
                            return put(("writer-" + i).getBytes(StandardCharsets.UTF_8), base, null)
                                    .eTag();
                        } catch (S3Exception e) {
                            if (e.statusCode() == 412) {
                                refused.incrementAndGet();
                            } else {
                                other.incrementAndGet();
                            }
                            return null;
                        }
                    })
                    .toList();

            List<Future<String>> submitted = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(1, TimeUnit.MINUTES)).isTrue();
            go.countDown();

            long winners = 0;
            for (Future<String> f : submitted) {
                if (f.get(2, TimeUnit.MINUTES) != null) {
                    winners++;
                }
            }

            assertThat(winners)
                    .as("compare-and-swap means exactly one writer commits from a given base version")
                    .isEqualTo(1);
            assertThat(other.get())
                    .as("every loser must fail with 412, not with some other error")
                    .isZero();
            assertThat(refused.get()).isEqualTo(writers - 1);
        }
    }

    /** 5. ETags chain: the ETag a conditional PUT returns is the one the next PUT must present. */
    @Test
    @DisplayName("5. ETags chain correctly across successive conditional updates")
    void etagsChainAcrossUpdates() {
        String etag = currentEtag();

        for (int i = 0; i < 5; i++) {
            byte[] payload = ("generation-" + i).getBytes(StandardCharsets.UTF_8);
            PutObjectResponse response = put(payload, etag, null);

            assertThat(response.eTag()).isNotEqualTo(etag);
            assertThat(body()).isEqualTo(payload);

            String previous = etag;
            etag = response.eTag();

            assertThat(etag)
                    .as("the returned ETag must be the object's current ETag, or chaining breaks")
                    .isEqualTo(currentEtag());
            assertThatThrownBy(() -> put(INTRUDER, previous, null))
                    .as("the superseded ETag must stop being accepted")
                    .isInstanceOf(S3Exception.class);
        }
    }

    // --- helpers ------------------------------------------------------------

    private PutObjectResponse put(byte[] content, String ifMatch, String ifNoneMatch) {
        return putKey(KEY, content, ifMatch, ifNoneMatch);
    }

    private PutObjectResponse putKey(String key, byte[] content, String ifMatch, String ifNoneMatch) {
        PutObjectRequest.Builder request =
                PutObjectRequest.builder().bucket(BUCKET).key(key);
        if (ifMatch != null) {
            request.ifMatch(ifMatch);
        }
        if (ifNoneMatch != null) {
            request.ifNoneMatch(ifNoneMatch);
        }
        return s3.putObject(request.build(), RequestBody.fromBytes(content));
    }

    private String currentEtag() {
        return s3.headObject(h -> h.bucket(BUCKET).key(KEY)).eTag();
    }

    private byte[] body() {
        return bodyOf(KEY);
    }

    private byte[] bodyOf(String key) {
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build());
        return bytes.asByteArray();
    }
}
