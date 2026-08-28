package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.floci.testcontainers.FlociContainer;
import java.net.URI;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
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
 * <p>The container is the Floci project's own {@link FlociContainer} — the same class the Arconia
 * Floci dev service wraps, and the source of the image tag, port and wait strategy — rather than a
 * hand-rolled {@code GenericContainer} with a guessed port. The dev service itself is deliberately
 * not used yet: its auto-configuration is {@code @ConditionalOnClass} on Spring Cloud AWS, and
 * putting that on the classpath makes awspring's own {@code CredentialsProviderAutoConfiguration}
 * fail every gateway Spring context for want of an {@code AwsRegionProvider}. Decision 9 of the
 * {@code pluggable-git-storage} design records the evidence and the plan: adopt the dev service
 * once the object-store backend exists and the application configures AWS region and credentials
 * for real, at which point the conflict resolves itself.
 *
 * <p>This is the fidelity spike from task 2.1 of the {@code pluggable-git-storage} change. It
 * carries no {@code @SVCs} annotation: it verifies the store, not the gateway.
 */
@DisplayName("Object-store conditional-write fidelity")
class ConditionalWriteFidelityTests {

    private static final String BUCKET = "fidelity";
    private static final String KEY = "manifest";

    private static final byte[] ORIGINAL = "original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTRUDER = "intruder".getBytes(StandardCharsets.UTF_8);

    /**
     * Pinned, not {@code latest}: the store's conditional-write behaviour is the thing under test,
     * so the version that behaviour was observed on has to be the version CI runs. This is the tag
     * the Arconia Floci dev service resolves at Arconia 0.29.0, which keeps the two in step for the
     * eventual switch to the dev service.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("floci/floci:1.5.33");

    @SuppressWarnings("resource")
    private static final FlociContainer FLOCI = new FlociContainer(IMAGE);

    private static S3Client s3;

    @BeforeAll
    static void buildClient() {
        FLOCI.start();
        s3 = S3Client.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                // Path style: the emulator is reached by host:port, so a virtual-host bucket
                // prefix would resolve to a name that does not exist.
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    static void closeClient() {
        if (s3 != null) {
            s3.close();
            s3 = null;
        }
        FLOCI.stop();
    }

    @BeforeEach
    void resetObject() {
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

    private static PutObjectResponse put(byte[] content, String ifMatch, String ifNoneMatch) {
        return putKey(KEY, content, ifMatch, ifNoneMatch);
    }

    private static PutObjectResponse putKey(String key, byte[] content, String ifMatch, String ifNoneMatch) {
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

    private static String currentEtag() {
        return s3.headObject(h -> h.bucket(BUCKET).key(KEY)).eTag();
    }

    private static byte[] body() {
        return bodyOf(KEY);
    }

    private static byte[] bodyOf(String key) {
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build());
        return bytes.asByteArray();
    }
}
