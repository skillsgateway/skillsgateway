package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The conditional-write fidelity assertions, written once and run against every store the gateway
 * is willing to trust.
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
 * <p>Assertions M-A and M-B are <b>negative controls</b>, and they are the reason the other five
 * mean anything. The {@code pluggable-git-storage} spike established that the suite discriminates
 * by hand-editing the preconditions out and re-running — a step that is easy to skip and impossible
 * to audit afterwards. Here the same two mutations are standing tests: with the precondition
 * dropped the store must let the write through. A store that refuses writes for some unrelated
 * reason would answer 412 to everything and pass assertions 1–5 while proving nothing; these two
 * are what catch it, on every run, without anyone editing anything.
 *
 * <p>Subclasses supply the store. {@link ConditionalWriteFidelityTests} runs everything here
 * against Floci through the Arconia dev service, on every build; {@link
 * RealS3ConditionalWriteFidelityTests} runs the same assertions against a real AWS S3 bucket when
 * one is configured, and is skipped otherwise.
 *
 * <p>This carries no {@code @SVCs} annotation: it verifies the store, not the gateway.
 */
@DisplayName("Object-store conditional-write fidelity")
abstract class ConditionalWriteFidelitySuite {

    /** How many writers race off one barrier in assertion 4 and in mutation M-A. */
    private static final int WRITERS = 8;

    private static final byte[] ORIGINAL = "original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTRUDER = "intruder".getBytes(StandardCharsets.UTF_8);

    /** Every key this test wrote, so cleanup can delete exactly those and enumerate nothing. */
    private final Set<String> written = ConcurrentHashMap.newKeySet();

    // --- what a subclass must supply -----------------------------------------

    /** The client to talk to the store through. */
    protected abstract S3Client s3();

    /** The bucket to write into. It must already exist unless {@link #prepareStore()} creates it. */
    protected abstract String bucket();

    /**
     * A prefix every key this suite writes lives under. Empty for a throwaway emulator bucket; for a
     * shared real bucket it is run-scoped, so two runs cannot collide and neither can reach anything
     * that was already there.
     */
    protected String keyPrefix() {
        return "";
    }

    /** Runs before each test's fixture is written — the hook an emulator uses to create its bucket. */
    protected void prepareStore() {
        // Nothing by default: a real bucket is provisioned by the operator, not by the suite.
    }

    /**
     * Whether to delete the objects this suite wrote after each test. False for a container that is
     * thrown away wholesale; true for a real bucket, which the suite must leave as it found it.
     */
    protected boolean cleansUpAfterItself() {
        return false;
    }

    // --- fixture -------------------------------------------------------------

    @BeforeEach
    void resetObject() {
        // One @BeforeEach, not two: JUnit does not order lifecycle methods declared at the same
        // level, and a separate store-preparation hook ran after this one often enough to fail.
        prepareStore();
        put(ORIGINAL, null, null);
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        if (!cleansUpAfterItself()) {
            written.clear();
            return;
        }
        for (String key : written) {
            try {
                s3().deleteObject(d -> d.bucket(bucket()).key(key));
            } catch (RuntimeException e) {
                // Best effort. A key that cannot be deleted is reported by the runbook's final
                // sweep, and failing cleanup would mask the assertion result that matters.
            }
        }
        written.clear();
    }

    // --- the five assertions -------------------------------------------------

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
        Race race = race(currentEtag());

        assertThat(race.winners())
                .as("compare-and-swap means exactly one writer commits from a given base version")
                .isEqualTo(1);
        assertThat(race.otherErrors())
                .as("every loser must fail with 412, not with some other error")
                .isZero();
        assertThat(race.refused()).isEqualTo(WRITERS - 1);
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

    // --- the two mutations, as standing negative controls ---------------------

    /**
     * M-A. Drop {@code If-Match} from assertion 4's racing writers. Every one of them must now
     * commit. Eight winners instead of one is precisely the signature of a store that ignores
     * preconditions — so seeing it here, and only here, is what proves assertion 4's single winner
     * came from the precondition rather than from serialization the store would have done anyway.
     */
    @Test
    @DisplayName("M-A. Dropping If-Match lets every concurrent writer commit")
    void droppingIfMatchLetsEveryConcurrentWriterWin() throws Exception {
        Race race = race(null);

        assertThat(race.winners())
                .as("without a precondition there is nothing to refuse; if these are not all winners,"
                        + " assertion 4's single winner is not evidence of compare-and-swap")
                .isEqualTo(WRITERS);
        assertThat(race.refused()).isZero();
        assertThat(race.otherErrors()).isZero();
    }

    /**
     * M-B. Drop {@code If-None-Match} from assertion 3's second create. It must now overwrite,
     * proving assertion 3's 412 came from the precondition and not from the store refusing a second
     * write to a key it has already seen.
     */
    @Test
    @DisplayName("M-B. Dropping If-None-Match lets the second create overwrite")
    void droppingIfNoneMatchLetsTheSecondCreateOverwrite() {
        String key = "mutation-create-once-" + System.nanoTime();

        putKey(key, ORIGINAL, null, "*");
        putKey(key, INTRUDER, null, null);

        assertThat(bodyOf(key))
                .as("without If-None-Match the second write must land; otherwise assertion 3 proves nothing")
                .isEqualTo(INTRUDER);
    }

    // --- helpers ------------------------------------------------------------

    /** The outcome of {@link #WRITERS} writers racing off one barrier, optionally conditioned. */
    private record Race(long winners, int refused, int otherErrors) {}

    private Race race(String ifMatch) throws Exception {
        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            List<Callable<String>> tasks = IntStream.range(0, WRITERS)
                    .<Callable<String>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await(1, TimeUnit.MINUTES);
                        try {
                            return put(("writer-" + i).getBytes(StandardCharsets.UTF_8), ifMatch, null)
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
            return new Race(winners, refused.get(), other.get());
        }
    }

    protected final PutObjectResponse put(byte[] content, String ifMatch, String ifNoneMatch) {
        return putKey("manifest", content, ifMatch, ifNoneMatch);
    }

    protected final PutObjectResponse putKey(String key, byte[] content, String ifMatch, String ifNoneMatch) {
        String scoped = keyPrefix() + key;
        written.add(scoped);
        PutObjectRequest.Builder request =
                PutObjectRequest.builder().bucket(bucket()).key(scoped);
        if (ifMatch != null) {
            request.ifMatch(ifMatch);
        }
        if (ifNoneMatch != null) {
            request.ifNoneMatch(ifNoneMatch);
        }
        return s3().putObject(request.build(), RequestBody.fromBytes(content));
    }

    protected final String currentEtag() {
        return etagOf("manifest");
    }

    protected final String etagOf(String key) {
        return s3().headObject(h -> h.bucket(bucket()).key(keyPrefix() + key)).eTag();
    }

    protected final byte[] body() {
        return bodyOf("manifest");
    }

    protected final byte[] bodyOf(String key) {
        ResponseBytes<GetObjectResponse> bytes = s3().getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket())
                .key(keyPrefix() + key)
                .build());
        return bytes.asByteArray();
    }
}
