package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.TokenRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shape guarantees the scope and filter columns rest on, now that all five are {@code TEXT[]}.
 *
 * <p>The load-bearing one is that no scope list can ever be the empty array. The delimited form
 * could not express it; the array can, and on {@code api_scopes} it would be actively dangerous —
 * an empty array satisfies {@code IS NOT NULL}, so it would make a credential count as a machine
 * credential, forced to expire and barred from being session-derived, while granting no
 * administrative reach at all. Both of the constraints that ask {@code api_scopes IS NOT NULL}
 * would keep passing while meaning something they were not written to mean.
 *
 * <p>These assertions go against the database rather than the record, because the record is exactly
 * where the old {@code ""} footgun was invisible: {@code "".split(",")} returned a list of one
 * empty name, so a trimmed column read as a token scoped to a marketplace that cannot exist.
 */
class SchemaShapeTests extends AbstractGatewayTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private WebhookSubscriberRepository webhookSubscriberRepository;

    @Test
    @SVCs({"SVC_GW_AUTH_0020"})
    void an_empty_api_scope_array_is_refused_so_it_cannot_become_a_machine_credential_holding_nothing() {
        // The expiry matters: without one, `machine_credentials_expire` refuses the empty array
        // first and the case this test exists for never gets reached. With an expiry set, the
        // credential satisfies every pre-existing constraint, and the only thing standing between
        // it and being a machine credential that grants nothing is the cardinality rule.
        AccessToken token = tokenRepository.create(
                "shape-api",
                uniqueName("tok"),
                uniqueName("hash"),
                null,
                Instant.now().plus(Duration.ofDays(1)),
                null);

        assertThatThrownBy(() -> jdbc.sql("UPDATE access_tokens SET api_scopes = '{}' WHERE id = :id")
                        .param("id", token.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("scope_lists_are_never_empty");

        // NULL remains the way to say "no administrative reach", and it still means that.
        assertThat(tokenRepository.findById(token.id()).orElseThrow().machineCredential())
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0006"})
    void an_empty_fetch_scope_array_is_refused_so_it_cannot_be_confused_with_every_marketplace() {
        AccessToken token = tokenRepository.create("shape-fetch", uniqueName("tok"), uniqueName("hash"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE access_tokens SET scopes = '{}' WHERE id = :id")
                        .param("id", token.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("scope_lists_are_never_empty");

        // The two would have meant opposite things: NULL grants every marketplace, empty none.
        AccessToken stored = tokenRepository.findById(token.id()).orElseThrow();
        assertThat(stored.scopes()).isNull();
        assertThat(stored.permitsMarketplace("anything")).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0007"})
    void an_empty_push_scope_array_is_refused_and_null_still_means_no_push() {
        AccessToken token = tokenRepository.create("shape-push", uniqueName("tok"), uniqueName("hash"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE access_tokens SET push_scopes = '{}' WHERE id = :id")
                        .param("id", token.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("scope_lists_are_never_empty");

        assertThat(tokenRepository.findById(token.id()).orElseThrow().permitsPushTo("anything"))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0001"})
    void an_empty_event_filter_is_refused_rather_than_subscribing_to_nothing() {
        WebhookSubscriber subscriber = webhookSubscriberRepository.create(
                uniqueName("shape-sub"), "http://localhost/hook", "whsec_shape", List.of("*"));
        try {
            assertThatThrownBy(() -> jdbc.sql("UPDATE webhook_subscribers SET events = '{}' WHERE id = :id")
                            .param("id", subscriber.id())
                            .update())
                    .isInstanceOf(DataAccessException.class);
        } finally {
            webhookSubscriberRepository.delete(subscriber.id());
        }
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0006"})
    void a_scope_value_carrying_a_quote_or_a_backslash_round_trips_intact() {
        // Not reachable through the API, which validates scopes against registered names. Asserted
        // because the array literal is assembled by hand, and unescaped is how it would corrupt.
        AccessToken token = tokenRepository.create(
                "shape-escape",
                uniqueName("tok"),
                uniqueName("hash"),
                List.of("quote\"name", "back\\slash", "plain"),
                null,
                null);

        assertThat(tokenRepository.findById(token.id()).orElseThrow().scopeList())
                .containsExactly("quote\"name", "back\\slash", "plain");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0006"})
    void a_scope_value_carrying_the_old_delimiter_stays_one_scope() {
        // The delimited form could not represent this at all: it would have become two scopes, and
        // a marketplace named for either half would have matched a token that was never granted it.
        AccessToken token = tokenRepository.create(
                "shape-comma", uniqueName("tok"), uniqueName("hash"), List.of("alpha,beta"), null, null);

        AccessToken stored = tokenRepository.findById(token.id()).orElseThrow();
        assertThat(stored.scopeList()).containsExactly("alpha,beta");
        assertThat(stored.permitsMarketplace("alpha")).isFalse();
        assertThat(stored.permitsMarketplace("beta")).isFalse();
        assertThat(stored.permitsMarketplace("alpha,beta")).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0001"})
    void a_marketplace_name_breaking_the_scope_delimiter_pattern_is_refused_by_the_database() {
        // The API refuses this in MarketplaceRegistrationService; the constraint is what stops every
        // other write path, which is the reason the scope columns could assume the pattern held.
        assertThatThrownBy(
                        () -> jdbc.sql("INSERT INTO marketplaces (name, url, created_at) VALUES (:name, :url, now())")
                                .param("name", "alpha,beta")
                                .param("url", "file:///upstream")
                                .update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(
                        () -> jdbc.sql("INSERT INTO marketplaces (name, url, created_at) VALUES (:name, :url, now())")
                                .param("name", "Capitals")
                                .param("url", "file:///upstream")
                                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0001"})
    void the_vetting_run_trigger_is_a_native_enum_and_refuses_an_unknown_value() throws Exception {
        Registered registered = registerAndIngest(uniqueName("trigger"), createUpstream(DEFAULT_MANIFEST));
        long snapshotId = registered.snapshot().id();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO vetting_runs (snapshot_id, trigger, started_at)"
                                + " VALUES (:id, 'not-a-trigger'::vetting_run_trigger, now())")
                        .param("id", snapshotId)
                        .update())
                .isInstanceOf(DataAccessException.class);

        // The ingestion run the snapshot already has proves the accepted values still write.
        assertThat(jdbc.sql("SELECT trigger FROM vetting_runs WHERE snapshot_id = :id")
                        .param("id", snapshotId)
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .allSatisfy(trigger -> assertThat(trigger).isIn("ingestion", "revet-scheduled", "revet-manual"));
    }
}
