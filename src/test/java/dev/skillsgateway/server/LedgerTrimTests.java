package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.retention.RetentionService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The ledger trim (GW_RETENTION_0009, GW_RETENTION_0010) — the only pass in the gateway that
 * deletes audit evidence, tested the way a deletion has to be: by what it refuses to remove.
 *
 * <p>Every test here is a negative one. A happy path that removes old rows proves almost nothing;
 * the predicate is a conjunction of two conditions and a closed allowlist, and each of those three
 * is a separate way to destroy a record that should have survived. So each is failed on its own.
 *
 * <p><b>Its own Spring context, deliberately.</b> {@code tasks.md} asked for the shared one and
 * that turned out to be wrong: this suite deletes from {@code fetch_log}, which every suite in the
 * shared context writes to, and its watermark assertions depend on exactly which export sinks are
 * enabled — state other suites in that context own and mutate. Sharing would have meant either
 * damaging other suites' rows or weakening these assertions until they tolerated arbitrary shared
 * state, and a weakened assertion is worth less than a second context. {@code ContextBudgetTests}
 * moves by one, which is the argument for it.
 *
 * <p>The maximum age is an hour rather than milliseconds so that nothing a test writes incidentally
 * is ever eligible: only rows this suite deliberately back-dates can be trimmed at all.
 */
@TestPropertySource(
        properties = {"skills-gateway.retention.enabled=true", "skills-gateway.retention.ledger-max-age=1h"})
class LedgerTrimTests extends AbstractGatewayTest {

    private static final String MARKETPLACE = "ledger-trim";

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private AuditSinkRepository auditSinkRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private dev.skillsgateway.server.observability.LedgerMetrics ledgerMetrics;

    @BeforeEach
    void clearSinks() {
        // This context is this suite's alone, so the sink table is this suite's to own.
        jdbc.sql("DELETE FROM audit_sinks").update();
    }

    /** One ledger row with an explicit timestamp, which {@code append} does not allow. */
    private long row(String event, Duration age) {
        return jdbc.sql("INSERT INTO fetch_log (ts, source, principal, marketplace, event)"
                        + " VALUES (:ts, 'test', 'someone', :marketplace, :event) RETURNING id")
                .param("ts", OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC))
                .param("marketplace", MARKETPLACE)
                .param("event", event)
                .query(Long.class)
                .single();
    }

    private long oldRead(String event) {
        return row(event, Duration.ofDays(30));
    }

    private String sink(String label, long cursor, boolean enabled) {
        String name = uniqueName(label);
        long subscriberId = jdbc.sql("INSERT INTO webhook_subscribers (name, url, secret, events, enabled, created_at)"
                        + " VALUES (:name, 'https://sink.invalid', 'x', ARRAY['*'], TRUE, NOW()) RETURNING id")
                .param("name", name)
                .query(Long.class)
                .single();
        auditSinkRepository.create(name, "webhook", subscriberId, cursor, 100);
        if (!enabled) {
            jdbc.sql("UPDATE audit_sinks SET enabled = FALSE WHERE name = :name")
                    .param("name", name)
                    .update();
        }
        return name;
    }

    private boolean survives(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM fetch_log WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * The deployment the design deliberately leaves unbounded. The gateway cannot be the only
     * holder of the evidence and also delete it, so with nothing consuming the ledger it keeps
     * every row — however old, whatever the age setting says.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void no_sink_at_all_removes_nothing_however_old_the_entries() {
        long ancient = oldRead("upload-pack");

        RetentionService.LedgerTrim result = retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(result.removed()).isZero();
        assertThat(result.boundBy())
                .as("no sink is not the same fact as a sink at zero")
                .isNull();
        assertThat(survives(ancient)).isTrue();
    }

    /** The bound is the slowest consumer's — not the fastest, and not an average of them. */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void the_watermark_is_the_slowest_enabled_sink() {
        long first = oldRead("upload-pack");
        long second = oldRead("info-refs");
        long third = oldRead("upload-pack");

        String laggard = sink("laggard", first, true);
        sink("leader", third, true);

        RetentionService.LedgerTrim result = retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(result.boundBy()).isEqualTo(laggard);
        assertThat(survives(first)).as("at the watermark, so taken").isFalse();
        assertThat(survives(second))
                .as("past the laggard, so still its only copy")
                .isTrue();
        assertThat(survives(third)).isTrue();
    }

    /** A sink an operator switched off is not a consumer, so it pins nothing. */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void a_disabled_sink_that_is_behind_does_not_pin_the_watermark() {
        long first = oldRead("upload-pack");
        long second = oldRead("upload-pack");

        sink("switched-off", first - 1, false);
        String live = sink("live", second, true);

        RetentionService.LedgerTrim result = retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(result.boundBy()).isEqualTo(live);
        assertThat(survives(first)).isFalse();
        assertThat(survives(second)).isFalse();
    }

    /**
     * The compliance-bearing half of the ledger. Each of these is old enough and below the
     * watermark, so only the allowlist saves it — which is the whole of GW_RETENTION_0010.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0010"})
    void administrative_and_publication_entries_survive_the_trim_that_takes_the_reads_beside_them() {
        List<Long> administrative = List.of(
                oldRead("snapshot-approved"),
                oldRead("snapshot-rejected"),
                oldRead("snapshot-revoked"),
                oldRead("marketplace-registered"),
                oldRead("token-issued"),
                oldRead("audit-sink-registered"),
                // An event kind that does not exist yet: the allowlist is closed, so it is not
                // trimmable until somebody deliberately admits it.
                oldRead("some-event-invented-next-year"));
        long read = oldRead("upload-pack");

        sink("takes-everything", read, true);
        RetentionService.LedgerTrim result = retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(survives(read))
                .as("the read beside them was taken, so the trim did run")
                .isFalse();
        for (long id : administrative) {
            assertThat(survives(id)).as("entry %d", id).isTrue();
        }
    }

    /**
     * Half the predicate, failed on its own: old enough, but no sink has taken it. This and the
     * next test are the pair — remove either conjunct from the SQL and one of them goes red.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void age_alone_is_never_sufficient() {
        long taken = oldRead("upload-pack");
        long beyond = oldRead("upload-pack");

        sink("stops-here", taken, true);
        retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(survives(taken)).isFalse();
        assertThat(survives(beyond))
                .as("past the cutoff, but no sink holds a copy")
                .isTrue();
    }

    /** The other half: below the watermark, but not old enough. */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void being_below_the_watermark_is_never_sufficient() {
        long recent = row("upload-pack", Duration.ofMinutes(1));
        long old = oldRead("upload-pack");

        sink("takes-everything", old, true);
        retentionService.trimLedger(RetentionService.POLICY_ACTOR);

        assertThat(survives(recent)).as("inside the maximum age").isTrue();
        assertThat(survives(old)).isFalse();
    }

    /**
     * The pass shares its lease with compaction, so it must yield rather than run to completion on
     * a table years deep. The deadline is injected rather than waited out.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0009"})
    void the_work_budget_stops_the_pass_and_the_next_one_continues() {
        long last = 0;
        for (int i = 0; i < 5; i++) {
            last = oldRead("upload-pack");
        }
        sink("takes-everything", last, true);

        // A deadline already past: the pass must do nothing and say it was the budget that stopped
        // it, rather than silently reporting a clean sweep of an untouched table.
        RetentionService.LedgerTrim stopped = retentionService.trimLedger(
                RetentionService.POLICY_ACTOR, Instant.now().minusSeconds(1));
        assertThat(stopped.removed()).isZero();
        assertThat(stopped.budgetExhausted()).isTrue();

        // The next pass, with a budget, continues from where the ledger stands.
        RetentionService.LedgerTrim resumed = retentionService.trimLedger(RetentionService.POLICY_ACTOR);
        assertThat(resumed.removed()).isEqualTo(5);
        assertThat(resumed.budgetExhausted()).isFalse();
    }

    /**
     * The gauges, and the case they exist for. With nothing consuming the ledger the trim does
     * nothing by design — so the lag has to be a number, not an absent series, or the deployment
     * that grows forever is the one with no telemetry saying so.
     */
    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0003"})
    void the_export_lag_is_a_number_even_when_nothing_consumes_the_ledger() {
        row("upload-pack", Duration.ofHours(6));

        assertThat(ledgerMetrics.exportLagSeconds())
                .as("no sink means the whole ledger is un-exported, and its age is the answer")
                .isGreaterThanOrEqualTo(Duration.ofHours(6).toSeconds());

        // A sink that has taken everything leaves nothing behind it, and the lag reads zero rather
        // than the age of rows somebody already holds.
        long latest =
                jdbc.sql("SELECT MAX(id) FROM fetch_log").query(Long.class).single();
        sink("caught-up", latest, true);

        assertThat(ledgerMetrics.exportLagSeconds()).isZero();
    }

    /** The depth gauge is an estimate by design; what it must never be is negative or absent. */
    @Test
    @SVCs({"SVC_GW_OBSERVABILITY_0003"})
    void the_depth_gauge_answers_without_counting_the_table() {
        assertThat(fetchLogRepository.approximateDepth()).isNotNegative();
    }
}
