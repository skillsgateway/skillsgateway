package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.TokenRepository;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.vetting.ConnectorToggle;
import dev.skillsgateway.server.vetting.ConnectorToggleRepository;
import dev.skillsgateway.server.vetting.VerdictState;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The four properties the repositories' row mapping rests on, pinned as regressions.
 *
 * <p>Ten repositories map their rows with {@code query(SomeRecord.class)} rather than a
 * hand-written {@code (rs, rowNum) ->} mapper. That is only correct while Spring's
 * {@code DataClassRowMapper} and the PostgreSQL driver keep behaving as measured here: a
 * {@code timestamptz} reaching an {@code Instant} component unaided is the load-bearing one, and
 * the two failures are as important as the successes, because they are why two composite reads and
 * every Java-enum component are still hand-written. A framework or driver upgrade that changes any
 * of them should fail in this class, where the reason is written down, rather than in a
 * repository, where it is not.
 */
class DataClassRowMapperTests extends AbstractGatewayTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private ConnectorToggleRepository connectorToggleRepository;

    @Autowired
    private WebhookSubscriberRepository webhookSubscriberRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    /** A {@code timestamptz} reaches an {@code Instant} component with no converter of ours. */
    @Test
    void a_timestamptz_column_maps_to_an_instant_component() {
        Marketplace registered = marketplaceRepository.register(uniqueName("mapper-instant"), "file:///upstream");

        Marketplace read = jdbc.sql("SELECT * FROM marketplaces WHERE id = :id")
                .param("id", registered.id())
                .query(Marketplace.class)
                .single();

        assertThat(read.createdAt())
                .as("mapped to the same instant the hand-written mapper produced, to the microsecond")
                .isEqualTo(registered.createdAt());
        assertThat(read).isEqualTo(registered);

        // And directly, so a failure here separates "the driver changed" from "the record changed".
        OffsetDateTime raw = jdbc.sql("SELECT created_at FROM marketplaces WHERE id = :id")
                .param("id", registered.id())
                .query(OffsetDateTime.class)
                .single();
        assertThat(read.createdAt()).isEqualTo(raw.toInstant());
    }

    /** A nullable column reaches a boxed component as null, and a set one as its value. */
    @Test
    void a_null_column_maps_to_null_in_a_boxed_component() {
        Boxes read = jdbc.sql(
                        "SELECT NULL::bigint AS absent, 7::bigint AS present," + " NULL::timestamptz AS missing_stamp")
                .query(Boxes.class)
                .single();

        assertThat(read.absent()).isNull();
        assertThat(read.present()).isEqualTo(7L);
        assertThat(read.missingStamp()).isNull();
    }

    /**
     * A SQL NULL into a primitive component fails rather than silently becoming zero. This is why
     * every nullable column in this schema is a boxed component: the mapper cannot quietly turn an
     * absent id into id 0.
     */
    @Test
    void a_null_column_into_a_primitive_component_fails_rather_than_defaulting_to_zero() {
        assertThatThrownBy(() -> jdbc.sql("SELECT NULL::bigint AS primitive")
                        .query(Primitive.class)
                        .single())
                .hasMessageContaining("long");
    }

    /**
     * A native enum column does <em>not</em> reach a Java enum component: this project stores enum
     * labels lower-case and Spring's string-to-enum conversion is case-sensitive. It is why
     * {@code VettingRepository} still hand-maps through {@code VerdictState.of(...)} while the
     * repositories whose enum columns are {@code String} components do not have to.
     */
    @Test
    void a_native_enum_column_does_not_map_to_a_java_enum_component() {
        assertThatThrownBy(() -> jdbc.sql("SELECT 'pass'::vetting_verdict_state AS state")
                        .query(EnumComponent.class)
                        .single())
                .hasMessageContaining(VerdictState.class.getSimpleName());

        // The same column as a String component is fine, which is the shape the schema records use.
        assertThat(jdbc.sql("SELECT 'pass'::vetting_verdict_state AS state")
                        .query(StringComponent.class)
                        .single()
                        .state())
                .isEqualTo("pass");
    }

    /**
     * A column the record has no component for is ignored. This is what lets
     * {@code SnapshotRepository.candidates} select a computed {@code reason} alongside
     * {@code snapshots.*} and still map the snapshot half with the shared mapper.
     */
    @Test
    void a_surplus_column_is_ignored() {
        Marketplace registered = marketplaceRepository.register(uniqueName("mapper-surplus"), "file:///upstream");

        Marketplace read = jdbc.sql("SELECT *, 'held-too-long' AS reason FROM marketplaces WHERE id = :id")
                .param("id", registered.id())
                .query(Marketplace.class)
                .single();

        assertThat(read).isEqualTo(registered);
    }

    /**
     * A component the result set has no column for fails loudly. This is why a composite record
     * like {@code SnapshotClosureRepository.Closure}, whose {@code members} component is another
     * query's result, is still assembled by hand instead of being mapped and patched.
     */
    @Test
    void a_component_with_no_column_fails_rather_than_mapping_to_null() {
        assertThatThrownBy(() -> jdbc.sql("SELECT 1::bigint AS present")
                        .query(Boxes.class)
                        .single())
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("absent");
    }

    /**
     * The same property, end to end, through the three repositories that used to decide it with a
     * hand-written {@code rs.wasNull()} — the only places in the rollout where deleting the mapper
     * deleted a null decision rather than a straight column read. A mapper that turned an absent
     * value into zero would leave a token claiming to be a rotation of token 0, a per-marketplace
     * connector setting where a global one was written, and a queued webhook delivery reporting
     * HTTP 0 as its last status. None of those raise an error anywhere; they are only visible here.
     */
    @Test
    void an_absent_value_stays_absent_through_the_repositories_that_used_to_decide_it_by_hand() {
        AccessToken fresh = tokenRepository.create("mapper-null", uniqueName("tok"), uniqueName("hash"));
        assertThat(tokenRepository.findById(fresh.id()).orElseThrow().rotatedFrom())
                .as("a token that is not a rotation names no predecessor")
                .isNull();

        ConnectorToggle global = connectorToggleRepository.set(uniqueName("mapper-conn"), null, false, "why", "alice");
        assertThat(global.marketplaceId())
                .as("the global connector setting is scoped to no marketplace")
                .isNull();
        assertThat(connectorToggleRepository
                        .findGlobal(global.connector())
                        .orElseThrow()
                        .marketplaceId())
                .isNull();

        WebhookSubscriber subscriber =
                webhookSubscriberRepository.create(uniqueName("mapper-sub"), "http://localhost/hook", "s", "*");
        try {
            WebhookDelivery queued = webhookDeliveryRepository.enqueue(subscriber.id(), "snapshot.approved", "{}");
            assertThat(queued.lastStatus())
                    .as("a delivery that has not been attempted reports no status")
                    .isNull();
            assertThat(webhookDeliveryRepository
                            .findById(queued.id())
                            .orElseThrow()
                            .lastStatus())
                    .isNull();
        } finally {
            webhookSubscriberRepository.delete(subscriber.id());
        }

        Marketplace marketplace = marketplaceRepository.register(uniqueName("mapper-nulls"), "file:///upstream");
        assertThat(marketplace.upstreamUpdatedAt()).isNull();
        assertThat(marketplace.lastSyncAt()).isNull();

        Snapshot held = snapshotRepository.create(marketplace.id(), uniqueName("sha"), Snapshot.HELD, null, null);
        Snapshot read = snapshotRepository.findById(held.id()).orElseThrow();
        assertThat(read.decidedAt()).isNull();
        assertThat(read.revokedAt()).isNull();
        assertThat(read.deletedAt()).isNull();
        assertThat(read.purgeAfter()).isNull();
    }

    record Boxes(Long absent, Long present, Instant missingStamp) {}

    record Primitive(long primitive) {}

    record EnumComponent(VerdictState state) {}

    record StringComponent(String state) {}
}
