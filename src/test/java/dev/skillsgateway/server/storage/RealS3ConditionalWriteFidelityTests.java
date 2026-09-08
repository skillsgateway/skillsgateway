package dev.skillsgateway.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Runs {@link ConditionalWriteFidelitySuite} — the five assertions and both mutation controls,
 * unchanged in substance — against a <b>real AWS S3 bucket</b>.
 *
 * <p>This is issue #151. Every conditional-write assertion the object-store backend rests on has
 * only ever run against Floci, a local emulator, and that emulator has already been caught
 * diverging from S3 on the read path: it served a partially written object mid-overwrite, with a
 * matching {@code Content-Length} and its own ETag. An emulator agreeing with itself is not
 * evidence that the primitive is portable. So the same assertions are pointed at the production
 * target, and the store either enforces preconditions or it does not.
 *
 * <p><b>It is skipped unless a bucket is named</b>, and it is skipped rather than absent, so a
 * build that has not verified anything says so out loud instead of looking green. Point it at a
 * bucket with either an environment variable or a system property:
 *
 * <pre>
 *   SKILLS_GATEWAY_FIDELITY_S3_BUCKET   / -Dskills-gateway.fidelity.s3.bucket=&lt;bucket&gt;
 *   SKILLS_GATEWAY_FIDELITY_S3_REGION   / -Dskills-gateway.fidelity.s3.region=&lt;region&gt;
 * </pre>
 *
 * <p>Region is optional; without it the SDK's own region provider chain decides. Those two values
 * are the whole configuration surface. <b>Credentials are never handled here</b> — the client is
 * built with no credentials provider at all, so the default chain resolves them exactly as it does
 * for the gateway itself, and nothing about an account can end up in this repository.
 *
 * <p>Every object goes under one run-scoped prefix, and each test deletes exactly the keys it
 * wrote — recorded as they are written, never discovered by listing. The suite therefore never
 * enumerates the bucket and cannot reach a key it did not create, because a scratch bucket may not
 * be exclusively ours.
 *
 * <p>The last two tests are real-S3 specifics the emulator spike never exercised, named in #151:
 * read-after-CAS from an independent client and connection, and a conditional PUT racing the DELETE
 * that {@code unpublish} performs. The ETag-under-SSE-KMS and ETag-under-versioning questions need
 * no code — no assertion here ever <em>computes</em> an expected ETag, only chains the one the
 * store returned, so pointing this suite at a bucket configured that way is itself the test. The
 * runbook says to do exactly that.
 *
 * <p>Carries no {@code @SVCs} annotation, for the same reason its sibling does not: it verifies the
 * store, not the gateway. A gate that only ever skips could not verify anything anyway.
 *
 * @see <a href="https://github.com/skillsgateway/skillsgateway/issues/151">#151</a>
 */
@EnabledIf(
        value = "aBucketIsConfigured",
        disabledReason = "SKIPPED, not passed: no real-S3 bucket configured."
                + " Set SKILLS_GATEWAY_FIDELITY_S3_BUCKET (and optionally SKILLS_GATEWAY_FIDELITY_S3_REGION)"
                + " to run the conditional-write assertions against real AWS S3."
                + " See docs/manual/guides/verifying-an-object-store.md")
@DisplayName("Object-store conditional-write fidelity — real AWS S3 (issue #151)")
class RealS3ConditionalWriteFidelityTests extends ConditionalWriteFidelitySuite {

    private static final String BUCKET_PROPERTY = "skills-gateway.fidelity.s3.bucket";
    private static final String REGION_PROPERTY = "skills-gateway.fidelity.s3.region";

    /** One prefix per JVM run, so two runs against the same bucket cannot collide. */
    private static final String PREFIX = "skills-gateway-fidelity/" + UUID.randomUUID() + "/";

    private static S3Client client;

    /**
     * The gate. A property or an environment variable naming a bucket is the entire opt-in; nothing
     * here reads a credential, and no AWS call is made to decide.
     */
    static boolean aBucketIsConfigured() {
        return configured(BUCKET_PROPERTY) != null;
    }

    @BeforeAll
    static void openClient() {
        S3ClientBuilder builder = S3Client.builder();
        String region = configured(REGION_PROPERTY);
        if (region != null) {
            builder = builder.region(Region.of(region));
        }
        // No credentials provider: the SDK's default chain resolves them, as it does for the
        // gateway. This suite never sees, stores or logs a credential.
        client = builder.build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected S3Client s3() {
        return client;
    }

    @Override
    protected String bucket() {
        return configured(BUCKET_PROPERTY);
    }

    @Override
    protected String keyPrefix() {
        return PREFIX;
    }

    @Override
    protected boolean cleansUpAfterItself() {
        return true;
    }

    /**
     * 6. Read-after-CAS across connections. A replica that did not perform the write reads the
     * object through its own client and its own pool; it must see the committed bytes and the ETag
     * the winning PUT returned, not a cached predecessor.
     */
    @Test
    @DisplayName("6. A conditional write is visible to an independent client and connection")
    void anIndependentClientSeesTheCommittedWrite() {
        byte[] payload = ("committed-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String committed = put(payload, currentEtag(), null).eTag();

        try (S3Client reader = independentClient()) {
            assertThat(reader.getObjectAsBytes(g -> g.bucket(bucket()).key(PREFIX + "manifest"))
                            .asByteArray())
                    .as("a replica that did not write must still read what the compare-and-swap committed")
                    .isEqualTo(payload);
            assertThat(reader.headObject(h -> h.bucket(bucket()).key(PREFIX + "manifest"))
                            .eTag())
                    .as("the ETag the winning PUT returned must be the one another reader sees,"
                            + " or a second replica cannot condition its next write on it")
                    .isEqualTo(committed);
        }
    }

    /**
     * 7. A conditional PUT racing the DELETE that {@code unpublish} performs. The object is gone;
     * the write conditioned on its old ETag must be refused, and must not resurrect it. Real S3
     * answers 404 here rather than 412 — either is a refusal, and the assertion is that it refuses
     * and writes nothing, not which code it chooses.
     */
    @Test
    @DisplayName("7. A conditional PUT loses to a DELETE and does not resurrect the object")
    void aConditionalPutRacingADeleteIsRefused() {
        String etag = currentEtag();
        client.deleteObject(d -> d.bucket(bucket()).key(PREFIX + "manifest"));

        assertThatThrownBy(() -> put("resurrected".getBytes(StandardCharsets.UTF_8), etag, null))
                .as("a conditional write whose object was deleted underneath it must be refused")
                .isInstanceOf(S3Exception.class)
                .satisfies(thrown -> assertThat(((S3Exception) thrown).statusCode())
                        .as("expected a refusal — 404 for a missing object, or 412")
                        .isIn(404, 412));

        assertThatThrownBy(() -> currentEtag())
                .as("the refused write must not have recreated the object")
                .isInstanceOf(S3Exception.class);
    }

    private S3Client independentClient() {
        S3ClientBuilder builder = S3Client.builder();
        String region = configured(REGION_PROPERTY);
        if (region != null) {
            builder = builder.region(Region.of(region));
        }
        return builder.build();
    }

    /** A system property wins over the matching {@code SCREAMING_SNAKE_CASE} environment variable. */
    private static String configured(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT));
        }
        return value == null || value.isBlank() ? null : value;
    }
}
