package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.roles.RoleGrant;
import dev.skillsgateway.server.roles.RoleGrantRepository;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.Verdict;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverRepository;
import dev.skillsgateway.server.vetting.WaiverScope;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The enumerated columns are PostgreSQL types rather than {@code TEXT} with a {@code CHECK}
 * (GW_0125): the column carries the set, every value of it survives a write and a read through the
 * repository that owns it, and a value outside the set is refused by the database.
 *
 * <p>The refusal cases update no row on purpose ({@code WHERE id = -1}). PostgreSQL coerces the
 * literal to the column's type while planning the statement, so the refusal is proved without
 * arranging a victim row — and a column that is still {@code TEXT} would accept the statement and
 * quietly report zero rows, which is exactly the regression this guards.
 */
class NativeEnumColumnTests extends AbstractGatewayTest {

    /** Every converted column, its type, and the labels the type must carry, in declared order. */
    private static final List<EnumColumn> COLUMNS = List.of(
            new EnumColumn("marketplaces", "origin", "marketplace_origin", List.of("upstream", "hosted")),
            new EnumColumn(
                    "marketplaces", "push_policy", "marketplace_push_policy", List.of("append-only", "allow-rewrite")),
            new EnumColumn(
                    "marketplaces", "sync_mode", "marketplace_sync_mode", List.of("on-demand", "scheduled", "webhook")),
            new EnumColumn("snapshots", "state", "snapshot_state", List.of("held", "approved", "rejected", "revoked")),
            new EnumColumn(
                    "webhook_deliveries", "state", "webhook_delivery_state", List.of("pending", "delivered", "failed")),
            new EnumColumn("audit_sinks", "kind", "audit_sink_kind", List.of("webhook")),
            new EnumColumn("vetting_runs", "outcome", "vetting_run_outcome", List.of("clear", "blocked")),
            new EnumColumn(
                    "vetting_verdicts",
                    "state",
                    "vetting_verdict_state",
                    List.of("pass", "warn", "fail", "error", "pending", "disabled")),
            new EnumColumn(
                    "vetting_findings",
                    "severity",
                    "vetting_finding_severity",
                    List.of("info", "low", "medium", "high", "critical")),
            new EnumColumn("vetting_waivers", "scope_kind", "vetting_waiver_scope_kind", List.of("snapshot", "path")),
            new EnumColumn("role_grants", "role", "role_grant_role", List.of("admin", "approver", "auditor")));

    private static final String OUTSIDE_THE_SET = "not-a-member-of-the-set";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WebhookSubscriberRepository webhookSubscriberRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private AuditSinkRepository auditSinkRepository;

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private WaiverRepository waiverRepository;

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_enumerated_column_is_a_native_enum_type_carrying_exactly_its_value_set() {
        for (EnumColumn column : COLUMNS) {
            Map<String, Object> declared = jdbc.sql("SELECT data_type, udt_name FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = :table AND column_name = :column")
                    .param("table", column.table())
                    .param("column", column.column())
                    .query()
                    .singleRow();
            assertThat(declared.get("data_type"))
                    .as("%s.%s is a type, not text", column.table(), column.column())
                    .isEqualTo("USER-DEFINED");
            assertThat(declared.get("udt_name"))
                    .as("%s.%s carries its own type", column.table(), column.column())
                    .isEqualTo(column.type());

            List<String> labels = jdbc.sql("SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid"
                            + " WHERE t.typname = :type ORDER BY e.enumsortorder")
                    .param("type", column.type())
                    .query(String.class)
                    .list();
            assertThat(labels)
                    .as("%s admits exactly the values the CHECK constraint enumerated", column.type())
                    .containsExactlyElementsOf(column.labels());
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_enumerated_column_refuses_a_value_outside_its_set() {
        for (EnumColumn column : COLUMNS) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE " + column.table() + " SET " + column.column() + " = '"
                                    + OUTSIDE_THE_SET + "' WHERE id = -1")
                            .update())
                    .as("%s.%s refuses a value outside %s", column.table(), column.column(), column.type())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(column.type());
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void a_marketplace_origin_push_policy_and_sync_mode_round_trip() {
        Marketplace upstream = marketplaceRepository.register(
                uniqueName("enum-up"),
                "file:///upstream",
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null);
        assertThat(upstream.origin()).isEqualTo(Marketplace.ORIGIN_UPSTREAM);
        assertThat(upstream.pushPolicy()).isEqualTo(Marketplace.PUSH_APPEND_ONLY);
        // The column DEFAULT survives the conversion: nothing named the sync mode here.
        assertThat(upstream.syncMode()).isEqualTo(Marketplace.SYNC_ON_DEMAND);

        Marketplace hosted = marketplaceRepository.register(
                uniqueName("enum-host"), null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_ALLOW_REWRITE, null);
        assertThat(hosted.origin()).isEqualTo(Marketplace.ORIGIN_HOSTED);
        assertThat(hosted.pushPolicy()).isEqualTo(Marketplace.PUSH_ALLOW_REWRITE);

        for (String mode : List.of(Marketplace.SYNC_SCHEDULED, Marketplace.SYNC_WEBHOOK, Marketplace.SYNC_ON_DEMAND)) {
            assertThat(marketplaceRepository
                            .updateSyncMode(upstream.name(), mode, null)
                            .orElseThrow()
                            .syncMode())
                    .isEqualTo(mode);
            assertThat(marketplaceRepository
                            .findByName(upstream.name())
                            .orElseThrow()
                            .syncMode())
                    .isEqualTo(mode);
        }

        // The table-level constraint that reads both enum columns still holds (GW_0101).
        assertThatThrownBy(() -> marketplaceRepository.updateSyncMode(hosted.name(), Marketplace.SYNC_SCHEDULED, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("marketplaces_hosted_is_on_demand");
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_snapshot_state_round_trips() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("enum-snap"), "file:///upstream");
        for (String state : List.of(Snapshot.HELD, Snapshot.APPROVED, Snapshot.REJECTED, Snapshot.REVOKED)) {
            Snapshot written = snapshotRepository.create(marketplace.id(), "sha-" + state, state, null, null);
            assertThat(written.state()).isEqualTo(state);
            assertThat(snapshotRepository.findById(written.id()).orElseThrow().state())
                    .isEqualTo(state);
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_webhook_delivery_state_round_trips() {
        WebhookSubscriber subscriber =
                webhookSubscriberRepository.create(uniqueName("enum-sub"), "http://localhost/hook", "secret", "*");
        try {
            WebhookDelivery pending = webhookDeliveryRepository.enqueue(subscriber.id(), "snapshot.approved", "{}");
            assertThat(pending.state()).isEqualTo(WebhookDelivery.PENDING);

            webhookDeliveryRepository.markDelivered(pending.id(), 1, 200);
            assertThat(state(pending.id())).isEqualTo(WebhookDelivery.DELIVERED);

            webhookDeliveryRepository.markRetry(
                    pending.id(), 2, Instant.now().plus(Duration.ofMinutes(1)), 500, "boom");
            assertThat(state(pending.id())).isEqualTo(WebhookDelivery.PENDING);

            webhookDeliveryRepository.markFailed(pending.id(), 3, 500, "boom");
            assertThat(state(pending.id())).isEqualTo(WebhookDelivery.FAILED);
        } finally {
            // Scaffolding for the column, not a subscriber: left registered it would collect a
            // delivery from every lifecycle event the rest of the suite emits. Cascades the rows.
            webhookSubscriberRepository.delete(subscriber.id());
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void the_audit_sink_kind_round_trips() {
        WebhookSubscriber subscriber =
                webhookSubscriberRepository.create(uniqueName("enum-sink-sub"), "http://localhost/hook", "s", "*");
        try {
            AuditSink sink =
                    auditSinkRepository.create(uniqueName("enum-sink"), AuditSink.WEBHOOK, subscriber.id(), 0, 500);
            assertThat(sink.kind()).isEqualTo(AuditSink.WEBHOOK);
            assertThat(auditSinkRepository.findById(sink.id()).orElseThrow().kind())
                    .isEqualTo(AuditSink.WEBHOOK);
        } finally {
            webhookSubscriberRepository.delete(subscriber.id());
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_vetting_outcome_verdict_state_and_severity_round_trips() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("enum-vet"), "file:///upstream");
        Snapshot snapshot = snapshotRepository.create(marketplace.id(), "sha-vetting", Snapshot.HELD, null, null);

        long runId = vettingRepository.startRun(snapshot.id(), VettingRepository.TRIGGER_INGESTION, "test@1");
        // startRun writes the fail-closed outcome; both stored outcomes are exercised.
        assertThat(vettingRepository.run(runId).orElseThrow().outcome()).isEqualTo(VettingChain.Outcome.BLOCKED);
        vettingRepository.finishRun(runId, VettingChain.Outcome.CLEAR);
        assertThat(vettingRepository.run(runId).orElseThrow().outcome()).isEqualTo(VettingChain.Outcome.CLEAR);

        int position = 0;
        for (VerdictState state : VerdictState.values()) {
            vettingRepository.recordVerdict(
                    runId,
                    "connector-" + state.stored(),
                    position++,
                    new Verdict(state, findingPerSeverity(), null, null));
        }
        var verdicts = vettingRepository.run(runId).orElseThrow().verdicts();
        assertThat(verdicts).extracting(v -> v.state()).containsExactlyElementsOf(List.of(VerdictState.values()));
        for (var verdict : verdicts) {
            assertThat(verdict.findings())
                    .extracting(Finding::severity)
                    .containsExactlyElementsOf(List.of(Severity.values()));
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_waiver_scope_round_trips() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("enum-waiver"), "file:///upstream");
        for (WaiverScope scope : WaiverScope.values()) {
            var waiver = waiverRepository.create(
                    marketplace.id(),
                    "aws-access-key-id",
                    scope,
                    scope == WaiverScope.SNAPSHOT ? "abc123" : "plugins/hello",
                    "accepted for the test",
                    "alice",
                    Instant.now().plus(Duration.ofDays(1)));
            assertThat(waiver.scope()).isEqualTo(scope);
            assertThat(waiverRepository.findById(waiver.id()).orElseThrow().scope())
                    .isEqualTo(scope);
        }
    }

    @Test
    @SVCs({"SVC_GW_0125"})
    void every_role_grant_role_round_trips() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("enum-role"), "file:///upstream");
        String principal = uniqueName("enum-principal");
        for (String role : List.of(RoleGrant.ADMIN, RoleGrant.AUDITOR)) {
            RoleGrant grant =
                    roleGrantRepository.insert(principal, role, null, "alice").orElseThrow();
            assertThat(grant.role()).isEqualTo(role);
        }
        RoleGrant approver = roleGrantRepository
                .insert(principal, RoleGrant.APPROVER, marketplace.id(), "alice")
                .orElseThrow();
        assertThat(approver.role()).isEqualTo(RoleGrant.APPROVER);
        assertThat(roleGrantRepository.findById(approver.id()).orElseThrow().role())
                .isEqualTo(RoleGrant.APPROVER);
    }

    private static List<Finding> findingPerSeverity() {
        return List.of(Severity.values()).stream()
                .map(severity -> new Finding("rule-" + severity.stored(), severity, "plugins/hello", "found it"))
                .toList();
    }

    private String state(long deliveryId) {
        return webhookDeliveryRepository.findById(deliveryId).orElseThrow().state();
    }

    private record EnumColumn(String table, String column, String type, List<String> labels) {}
}
