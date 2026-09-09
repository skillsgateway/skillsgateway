package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.audit.AuditExportScheduler;
import dev.skillsgateway.server.mirror.MirrorReconciliationSweep;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.SweepLease;
import dev.skillsgateway.server.persistence.SweepLeaseRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.retention.RetentionScheduler;
import dev.skillsgateway.server.scheduling.SweepLeases;
import dev.skillsgateway.server.sync.SyncScheduler;
import dev.skillsgateway.server.vetting.RevetScheduler;
import dev.skillsgateway.server.vetting.WaiverExpirySweep;
import dev.skillsgateway.server.webhook.WebhookDispatcher;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sweep lease (GW_FACADE_0030): what it grants, what it refuses, and what it never does.
 *
 * <p>The last of those is the reason the mechanism is a row and not a {@code pg_advisory_lock}. An
 * advisory lock is held by a database <em>session</em>, and under a connection pool the session
 * outlives the code that took it; a lease with an expiry cannot be leaked, because nothing has to
 * give it back. So the tests that matter most here are the ones about lapsing.
 */
class SweepCoordinationTests extends AbstractGatewayTest {

    /** Another replica, as far as the lease table is concerned. */
    private static final String OTHER_REPLICA = "skills-gateway-7d9f4c-xyz99";

    @Autowired
    private SweepLeases leases;

    @Autowired
    private SweepLeaseRepository leaseRepository;

    @Autowired
    private AuditSinkRepository sinkRepository;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void aHeldLeaseRefusesTheSweepWithoutWaitingForIt() {
        String key = uniqueName("lease-refused");
        AtomicInteger ran = new AtomicInteger();

        assertThat(leases.runIfLeader(key, Duration.ofMinutes(30), ran::incrementAndGet))
                .as("an unheld lease is granted")
                .isTrue();
        assertThat(ran).hasValue(1);

        // The pass is due again while this replica's own turn is still running out. It is skipped,
        // and — the part that cannot be asserted directly but is why this returns rather than
        // blocks — the single scheduling thread is free for the next sweep in the queue.
        Instant before = Instant.now();
        assertThat(leases.runIfLeader(key, Duration.ofMinutes(30), ran::incrementAndGet))
                .as("a lease already held is refused, not queued for")
                .isFalse();
        assertThat(Duration.between(before, Instant.now()))
                .as("refusing must not wait: a six-hourly lease would stall the five-second poll behind it")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(ran).as("the body did not run a second time").hasValue(1);
    }

    /**
     * The crash path, which is the whole argument for an expiry. Nothing releases this lease; the
     * next pass takes it because the turn is over.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void aLeaseNobodyReleasedIsTakenAgainOnceItHasLapsed() {
        String key = uniqueName("lease-lapsed");
        Instant now = Instant.now();

        // A replica that died holding it: the row stands, its turn is over, and no cleanup ran.
        assertThat(leaseRepository.claim(key, OTHER_REPLICA, now.minusSeconds(1), now.minusSeconds(60)))
                .isTrue();

        AtomicInteger ran = new AtomicInteger();
        assertThat(leases.runIfLeader(key, Duration.ofMinutes(30), ran::incrementAndGet))
                .as("a lapsed lease is taken without any release having happened")
                .isTrue();
        assertThat(ran).hasValue(1);
        assertThat(leaseRepository.find(key))
                .get()
                .extracting(SweepLease::holder)
                .as("and the new holder replaces the dead one")
                .isEqualTo(leases.holder());
    }

    /**
     * Two keys, no interference. This is why the retention passes have separate constants: they run
     * on different intervals, and one key would make the six-hourly compaction a six-hour outage
     * for the hourly evaluation.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void oneSweepsLeaseDoesNotHoldAnotherOut() {
        String held = uniqueName("lease-a");
        String free = uniqueName("lease-b");
        Instant now = Instant.now();
        assertThat(leaseRepository.claim(held, OTHER_REPLICA, now.plus(Duration.ofHours(6)), now))
                .isTrue();

        AtomicInteger ran = new AtomicInteger();
        assertThat(leases.runIfLeader(held, Duration.ofHours(6), ran::incrementAndGet))
                .isFalse();
        assertThat(leases.runIfLeader(free, Duration.ofHours(1), ran::incrementAndGet))
                .as("a different pass is unaffected by this one's turn")
                .isTrue();
        assertThat(ran).hasValue(1);

        assertThat(RetentionScheduler.EVALUATE_LEASE)
                .as("the two retention passes must never share a key")
                .isNotEqualTo(RetentionScheduler.COMPACT_LEASE);
    }

    /**
     * A pass that threw still had its turn. The alternative — releasing on failure — would let a
     * sweep that fails fast be retried by every replica in the estate, which is the multiplication
     * the lease exists to stop.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void aSweepThatThrewKeepsItsTurn() {
        String key = uniqueName("lease-threw");
        assertThat(catchThrowable(() -> leases.runIfLeader(key, Duration.ofMinutes(30), () -> {
                    throw new IllegalStateException("the pass failed");
                })))
                .isInstanceOf(IllegalStateException.class);

        AtomicInteger ran = new AtomicInteger();
        assertThat(leases.runIfLeader(key, Duration.ofMinutes(30), ran::incrementAndGet))
                .as("a failed pass does not hand its turn straight back")
                .isFalse();
        assertThat(ran).hasValue(0);
    }

    /** Which replica ran the pass has to be answerable afterwards, or the column is decoration. */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void theLeaseRecordsWhoTookIt() {
        String key = uniqueName("lease-holder");
        assertThat(leases.runIfLeader(key, Duration.ofMinutes(30), () -> {})).isTrue();

        assertThat(leaseRepository.find(key)).get().satisfies(lease -> {
            assertThat(lease.holder()).isNotBlank();
            assertThat(lease.holder()).isEqualTo(leases.holder());
            assertThat(lease.leasedUntil().toInstant()).isAfter(Instant.now());
        });
    }

    /**
     * The keys as the sweeps themselves declare them. Asserted as values rather than derived,
     * because a key that changes is a key under which two versions of the gateway coordinate
     * separately — a rolling upgrade with two leaders and nothing to say so.
     */
    @Test
    @SVCs({"SVC_GW_FACADE_0030"})
    void everySweepsKeyIsStableAndItsOwn() {
        List<String> keys = List.of(
                SyncScheduler.LEASE,
                RevetScheduler.LEASE,
                RetentionScheduler.EVALUATE_LEASE,
                RetentionScheduler.COMPACT_LEASE,
                WaiverExpirySweep.LEASE,
                WebhookDispatcher.LEASE,
                MirrorReconciliationSweep.LEASE,
                AuditExportScheduler.LEASE);

        assertThat(keys)
                .containsExactly(
                        "sync",
                        "revet",
                        "retention-evaluate",
                        "retention-compact",
                        "waiver-expiry",
                        "webhook-dispatch",
                        "mirror-drift",
                        "audit-export")
                .doesNotHaveDuplicates();
    }

    /**
     * The export cursor's compare-and-set (GW_AUDIT_0005). The failure it closes is not a duplicate
     * delivery — the lease is what stops there being two exporters — it is an export pass writing
     * the cursor forward over an operator's replay rewind, so the replay reports success and
     * delivers nothing.
     */
    @Test
    @SVCs({"SVC_GW_AUDIT_0005"})
    void anExportAdvanceCannotOverwriteACursorItDidNotRead() {
        AuditSink sink = newSink(uniqueName("cas"));

        assertThat(sinkRepository.advanceCursor(sink.id(), 100L, sink.cursorPosition()))
                .as("an advance from where the pass read is taken")
                .isPresent();
        assertThat(sinkRepository.findById(sink.id()).orElseThrow().cursorPosition())
                .isEqualTo(100L);

        // The operator rewinds for a replay. Rewinding is unconditional on purpose: overriding the
        // current value is the whole operation, so there is nothing to compare against.
        assertThat(sinkRepository.updateCursor(sink.id(), 10L)).isPresent();

        // An export pass that read 100 before the rewind now tries to advance to 200.
        assertThat(sinkRepository.advanceCursor(sink.id(), 200L, 100L))
                .as("an advance from a cursor that has moved under it is refused")
                .isEmpty();
        assertThat(sinkRepository.findById(sink.id()).orElseThrow().cursorPosition())
                .as("the replay stands; the batch is re-sent, which is the direction GW_AUDIT_0004 chooses")
                .isEqualTo(10L);
    }

    private AuditSink newSink(String name) {
        long subscriberId = subscriberRepository
                .create(name, "https://siem.invalid/ingest", "whsec_" + name, "audit.export")
                .id();
        return sinkRepository.create(name, "webhook", subscriberId, 0L, 500);
    }

    private static Throwable catchThrowable(Runnable body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
