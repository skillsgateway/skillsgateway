package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.PublicationLock;
import dev.skillsgateway.server.approval.PublicationReconciler;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every change to what a marketplace serves holds that marketplace's publication lock across the
 * record and the reference write (GW_APPROVAL_0032). Each test holds the lock from another thread,
 * as an in-flight transition would, and checks that the path under test waits for it rather than
 * acting on a state that is about to change.
 */
class PublicationLockTests extends AbstractGatewayTest {

    @Autowired
    private PublicationLock publicationLock;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private PublicationReconciler reconciler;

    @Autowired
    private GitStorage storage;

    @Autowired
    private JdbcClient jdbc;

    /** Holds the marketplace's lock on another thread, running {@code whileHeld} once released. */
    private CompletableFuture<Void> holdLock(
            long marketplaceId, CountDownLatch held, CountDownLatch release, Runnable whileHeld) {
        return CompletableFuture.runAsync(() -> publicationLock.holding(marketplaceId, () -> {
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            whileHeld.run();
            return null;
        }));
    }

    private boolean someoneWaitsOnAnAdvisoryLock() {
        return jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void awaitWaiter() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!someoneWaitsOnAnAdvisoryLock()) {
            assertThat(Instant.now())
                    .as("the transition never waited on the publication lock")
                    .isBefore(deadline);
            Thread.sleep(50);
        }
    }

    private <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0033"})
    void an_approval_waits_for_the_marketplace_lock_and_then_publishes() throws Exception {
        Registered registered = registerAndIngest(uniqueName("lockapprove"), createUpstream(DEFAULT_MANIFEST));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdLock(registered.marketplace().id(), held, release, () -> {});
        held.await(5, TimeUnit.SECONDS);

        CompletableFuture<ApprovalService.Approved> approving = async(() ->
                approvalService.approve(registered.snapshot().id(), "alice", ApprovalService.ApprovalOverride.none()));
        awaitWaiter();
        assertThat(storage.publishedIfServing(registered.marketplace().name()))
                .as("served while waiting")
                .isEmpty();

        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        approving.get(20, TimeUnit.SECONDS);
        assertThat(storage.publishedIfServing(registered.marketplace().name())).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0033"})
    void a_rollback_does_not_republish_a_predecessor_withdrawn_while_it_waited() throws Exception {
        String name = uniqueName("lockrollback");
        java.nio.file.Path upstream = createUpstream(DEFAULT_MANIFEST);
        Registered first = registerAndIngest(name, upstream);
        approve(first.snapshot().id());
        addUpstreamCommit(upstream, "second");
        Snapshot second = ingestionService.ingest(first.marketplace(), null);
        approve(second.id());

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The concurrent revoker: it withdraws the predecessor while it holds the lock.
        CompletableFuture<Void> holder = holdLock(first.marketplace().id(), held, release, () -> {
            snapshotRepository.revoke(first.snapshot().id(), "root", "withdrawn concurrently", "administrative");
            try {
                storage.unpublish(name, first.snapshot().sha());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        held.await(5, TimeUnit.SECONDS);

        CompletableFuture<RevocationService.Revoked> revoking = async(() -> revocationService.revoke(
                second.id(), "withdrawn", RevocationService.ServeAfter.PREVIOUS_APPROVED, "admin-b"));
        awaitWaiter();
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        RevocationService.Revoked revoked = revoking.get(20, TimeUnit.SECONDS);

        assertThat(revoked.rolledBackTo())
                .as("rolled back to a withdrawn snapshot")
                .isEmpty();
        assertThat(storage.publishedIfServing(name))
                .as("the withdrawn predecessor is served")
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0033"})
    void the_reconciler_does_not_republish_a_snapshot_withdrawn_while_it_waited() throws Exception {
        String name = uniqueName("lockreconcile");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());
        // Approved but not served: what the reconciler exists to repair.
        storage.unpublish(name, registered.snapshot().sha());

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdLock(
                registered.marketplace().id(),
                held,
                release,
                () -> snapshotRepository.revoke(
                        registered.snapshot().id(), "root", "withdrawn concurrently", "administrative"));
        held.await(5, TimeUnit.SECONDS);

        CompletableFuture<PublicationReconciler.Reconciliation> reconciling = async(() -> reconciler.reconcile("test"));
        awaitWaiter();
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        reconciling.get(30, TimeUnit.SECONDS);

        assertThat(storage.publishedIfServing(name))
                .as("the withdrawn snapshot was republished")
                .isEmpty();
    }
}
