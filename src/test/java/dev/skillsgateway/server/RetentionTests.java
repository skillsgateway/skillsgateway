package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.retention.RetentionService;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Snapshot retention: criteria evaluation (GW_RETENTION_0001), soft deletion with a restore window
 * (GW_RETENTION_0002), the approved-snapshot guard (GW_RETENTION_0003), hard-delete compaction including the git
 * storage (GW_RETENTION_0004), and the ledger record of all of it (GW_RETENTION_0005).
 */
class RetentionTests extends AbstractGatewayTest {

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private GitStorage storage;

    @Autowired
    private JdbcClient jdbc;

    /** Ingests the current upstream head as a new snapshot of the marketplace. */
    private Snapshot ingest(Marketplace marketplace) {
        return ingestionService.ingest(marketplace, null);
    }

    private boolean hasPin(String marketplace, String sha) throws IOException {
        try (Repository repository = storage.quarantine(marketplace)) {
            return repository.exactRef("refs/snapshots/" + sha) != null;
        }
    }

    private List<Map<String, Object>> ledgerFor(String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> marketplace.equals(entry.get("marketplace")))
                .toList();
    }

    private static boolean hasEvent(List<Map<String, Object>> entries, String event, String sha) {
        return entries.stream()
                .anyMatch(entry -> event.equals(entry.get("event"))
                        && "alice".equals(entry.get("principal"))
                        && (sha == null || sha.equals(entry.get("sha"))));
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0001"})
    void policySelectsTheAgedAndSupersededSnapshotsAndNothingElse() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("aged");
        Registered registered = registerAndIngest(name, upstream);
        Marketplace marketplace = registered.marketplace();
        Snapshot held = registered.snapshot();

        addUpstreamCommit(upstream, "second");
        Snapshot rejected = ingest(marketplace);
        approvalService.reject(rejected.id(), "alice");

        addUpstreamCommit(upstream, "third");
        Snapshot approved = ingest(marketplace);
        approve(approved.id());

        // Held like the first, but fetched through the facade a moment ago: the last-served guard
        // vetoes it whatever criterion matched.
        addUpstreamCommit(upstream, "fourth");
        Snapshot recentlyServed = ingest(marketplace);
        fetchLogRepository.append("127.0.0.1", "alice", name, "fetch", "main", recentlyServed.sha());

        List<RetentionService.Candidate> candidates = retentionService.candidates(name);

        assertThat(candidates)
                .extracting(RetentionService.Candidate::snapshotId, RetentionService.Candidate::reason)
                .containsExactlyInAnyOrder(tuple(held.id(), "held-too-long"), tuple(rejected.id(), "superseded"));
        assertThat(candidates)
                .extracting(RetentionService.Candidate::snapshotId)
                .doesNotContain(approved.id(), recentlyServed.id());
    }

    /**
     * The last-served guard is about this marketplace's traffic. A SHA is not unique across
     * marketplaces — a fork, a mirror, or the same upstream registered twice all carry it — so a
     * fetch of another marketplace's identically-pinned snapshot must not veto this one, which
     * would otherwise hold it in quarantine for as long as the other marketplace stays in use.
     */
    @Test
    @SVCs({"SVC_GW_RETENTION_0001"})
    void aFetchOfAnotherMarketplaceSharingTheCommitDoesNotVetoSelection() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String mine = uniqueName("shared");
        String theirs = uniqueName("mirror");
        Registered registered = registerAndIngest(mine, upstream);
        Registered mirror = registerAndIngest(theirs, upstream);
        assertThat(mirror.snapshot().sha()).isEqualTo(registered.snapshot().sha());

        fetchLogRepository.append(
                "127.0.0.1",
                "bob",
                theirs,
                "upload-pack",
                "main",
                mirror.snapshot().sha());

        assertThat(retentionService.candidates(mine))
                .extracting(RetentionService.Candidate::snapshotId, RetentionService.Candidate::reason)
                .contains(tuple(registered.snapshot().id(), "held-too-long"));
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0002"})
    void deletionMarksTheSnapshotAndARestoreInsideTheWindowClearsIt() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered registered = registerAndIngest(uniqueName("softdel"), upstream);
        long id = registered.snapshot().id();

        mockMvc.perform(delete("/api/v1/snapshots/" + id).with(oidcLogin())).andExpect(status().isOk());

        Snapshot deleted = snapshotRepository.findById(id).orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.deletedReason()).isEqualTo(RetentionService.MANUAL_REASON);
        assertThat(deleted.purgeAfter()).isAfter(Instant.now());
        // The vetting state is untouched by deletion: the snapshot was, and remains, held.
        assertThat(deleted.state()).isEqualTo(Snapshot.HELD);

        mockMvc.perform(post("/api/v1/snapshots/%d/restore".formatted(id)).with(oidcLogin()))
                .andExpect(status().isOk());

        Snapshot restored = snapshotRepository.findById(id).orElseThrow();
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.purgeAfter()).isNull();
        assertThat(restored.state()).isEqualTo(Snapshot.HELD);
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0003"})
    void anApprovedSnapshotIsNeverSelectedAndCannotBeDeleted() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("served");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot served = approve(registered.snapshot().id());

        // A later approval supersedes it and its quarantine age is far past the threshold, so every
        // criterion would match were the approved state not categorically ineligible.
        addUpstreamCommit(upstream, "successor");
        approve(ingest(registered.marketplace()).id());

        assertThat(retentionService.candidates(name))
                .extracting(RetentionService.Candidate::snapshotId)
                .doesNotContain(served.id());

        mockMvc.perform(delete("/api/v1/snapshots/" + served.id()).with(oidcLogin()))
                .andExpect(status().isConflict());

        retentionService.evaluate("alice", name);
        retentionService.compact("alice");

        assertThat(snapshotRepository.findById(served.id()).orElseThrow().deleted())
                .isFalse();
        // Still published: a clone of the facade repository succeeds.
        Path clone = newWorkDir("served-clone");
        assertThat(gitClone(facadeUrl(name, newPat()), clone.resolve("repo")).exitCode())
                .isZero();
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0004"})
    void compactionRemovesExpiredDeletionsAndTheirQuarantineReference() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("compact");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot expired = registered.snapshot();
        addUpstreamCommit(upstream, "second");
        Snapshot inWindow = ingest(registered.marketplace());

        snapshotRepository.softDelete(
                expired.id(), "held-too-long", Instant.now().minus(1, ChronoUnit.MINUTES));
        snapshotRepository.softDelete(
                inWindow.id(), "held-too-long", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(hasPin(name, expired.sha())).isTrue();

        retentionService.compact("alice");

        assertThat(snapshotRepository.findById(expired.id())).isEmpty();
        assertThat(hasPin(name, expired.sha())).isFalse();
        // The snapshot still inside its restore window keeps both its record and its git storage.
        assertThat(snapshotRepository.findById(inWindow.id()).orElseThrow().deleted())
                .isTrue();
        assertThat(hasPin(name, inWindow.sha())).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0005"})
    void theLedgerRecordsEveryRetentionAction() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String name = uniqueName("audited");
        Registered registered = registerAndIngest(name, upstream);
        Snapshot snapshot = registered.snapshot();

        RetentionService.PassResult pass = retentionService.evaluate("alice", name);
        assertThat(pass.acted()).isPositive();
        retentionService.restore(snapshot.id(), "alice");

        snapshotRepository.softDelete(
                snapshot.id(), "held-too-long", Instant.now().minus(1, ChronoUnit.MINUTES));
        retentionService.compact("alice");

        List<Map<String, Object>> entries = ledgerFor(name);
        assertThat(entries)
                .extracting(entry -> (String) entry.get("event"))
                .anyMatch(event -> event.startsWith("retention-evaluated:"));
        assertThat(hasEvent(entries, "snapshot-soft-deleted:held-too-long", snapshot.sha()))
                .isTrue();
        assertThat(hasEvent(entries, "snapshot-restored", snapshot.sha())).isTrue();
        assertThat(hasEvent(entries, "snapshot-purged", snapshot.sha())).isTrue();
    }

    private long subscriber() {
        return jdbc.sql("INSERT INTO webhook_subscribers (name, url, secret, events, enabled, created_at)"
                        + " VALUES (:name, 'https://sweep.invalid', 'x', ARRAY['*'], TRUE, NOW()) RETURNING id")
                .param("name", uniqueName("sweep"))
                .query(Long.class)
                .single();
    }

    /** A delivery row in the given state, last touched {@code age} ago. */
    private long delivery(long subscriberId, String state, Duration age) {
        return jdbc.sql("INSERT INTO webhook_deliveries (subscriber_id, event, payload, state, attempts,"
                        + " next_attempt_at, created_at, updated_at) VALUES (:s, 'x', '{}', CAST(:state AS"
                        + " webhook_delivery_state), 1, :at, :at, :at) RETURNING id")
                .param("s", subscriberId)
                .param("state", state)
                .param("at", OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private boolean exists(long deliveryId) {
        return jdbc.sql("SELECT COUNT(*) FROM webhook_deliveries WHERE id = :id")
                        .param("id", deliveryId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private long sweepEntries() {
        return fetchLogRepository.list().stream()
                .filter(entry -> String.valueOf(entry.get("event")).startsWith("webhook-deliveries-swept:"))
                .count();
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0011"})
    void compactionSweepsOnlySettledDeliveriesPastTheAgeAndNeverPendingOnes() {
        jdbc.sql("DELETE FROM webhook_deliveries WHERE updated_at < NOW() - INTERVAL '30 days'")
                .update();
        long before = sweepEntries();
        long sub = subscriber();
        Duration old = RetentionService.DELIVERY_MAX_AGE.plusDays(1);
        long oldDelivered = delivery(sub, "delivered", old);
        long oldFailed = delivery(sub, "failed", old);
        long oldPending = delivery(sub, "pending", old);
        long recentDelivered = delivery(sub, "delivered", RetentionService.DELIVERY_MAX_AGE.minusDays(1));
        long recentFailed = delivery(sub, "failed", Duration.ofMinutes(1));

        retentionService.compact("alice");

        assertThat(exists(oldDelivered)).isFalse();
        assertThat(exists(oldFailed)).isFalse();
        assertThat(exists(oldPending)).isTrue();
        assertThat(exists(recentDelivered)).isTrue();
        assertThat(exists(recentFailed)).isTrue();
        assertThat(sweepEntries()).isEqualTo(before + 1);
        assertThat(ledgerFor("-")).anyMatch(e -> "webhook-deliveries-swept:removed=2".equals(e.get("event")));

        retentionService.compact("alice");
        assertThat(sweepEntries())
                .as("a pass that removes nothing writes no entry")
                .isEqualTo(before + 1);
    }

    @Test
    @SVCs({"SVC_GW_RETENTION_0011"})
    void theDeliverySweepRemovesNoMoreThanOneBatchPerPass() {
        jdbc.sql("DELETE FROM webhook_deliveries WHERE updated_at < NOW() - INTERVAL '30 days'")
                .update();
        long sub = subscriber();
        int batch = 200;
        jdbc.sql("INSERT INTO webhook_deliveries (subscriber_id, event, payload, state, attempts,"
                        + " next_attempt_at, created_at, updated_at)"
                        + " SELECT :s, 'x', '{}', 'delivered', 1, NOW() - INTERVAL '40 days',"
                        + " NOW() - INTERVAL '40 days', NOW() - INTERVAL '40 days' FROM generate_series(1, :n)")
                .param("s", sub)
                .param("n", batch + 5)
                .update();

        assertThat(retentionService.sweepWebhookDeliveries("alice")).isEqualTo(batch);
        assertThat(retentionService.sweepWebhookDeliveries("alice")).isEqualTo(5);
    }

    /**
     * The indexes exist as shaped. Whether the planner picks one is not asserted: on a table this
     * small it chooses by cost, whichever index is cheapest to scan in full, so a plan assertion
     * would be a flaky test rather than evidence.
     */
    @Test
    void theLedgerAndDeliveryIndexesAreShapedForTheirQueries() {
        assertThat(indexDef("idx_fetch_log_sha_marketplace_ts"))
                .contains("(sha, marketplace, ts)")
                .contains("sha IS NOT NULL");
        assertThat(indexDef("idx_fetch_log_ts")).contains("(ts)").contains("upload-pack");
        assertThat(indexDef("idx_webhook_deliveries_subscriber")).contains("(subscriber_id, id)");
    }

    private String indexDef(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
                .param("name", name)
                .query(String.class)
                .single();
    }
}
