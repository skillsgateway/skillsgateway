package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.admin.MarketplaceRegistrationService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceUrlChangedException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A URL change racing an ingest (GW_INGEST_0066): content fetched from one URL must never be recorded
 * under another. Each interleaving is forced rather than hoped for — one side holds the marketplace row
 * open while the other is shown to be waiting on it — so a passing run is the guard working, not
 * the scheduler being kind.
 */
class MarketplaceUrlChangeRaceTests extends AbstractGatewayTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private MarketplaceRegistrationService registrationService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GitStorage storage;

    @Test
    @SVCs({"SVC_GW_INGEST_0066.1"})
    void an_ingest_that_fetched_before_the_change_records_nothing() throws Exception {
        String name = uniqueName("raceingest");
        Marketplace fetchedBefore = marketplaceRepository.register(
                name, createUpstream(DEFAULT_MANIFEST).toUri().toString());
        registrationService.changeUrl(
                name,
                createUpstream(DEFAULT_MANIFEST).toUri().toString(),
                "root",
                MarketplaceRegistrationService.Reachability.REFUSE);

        // The record the ingest was started with still carries the URL it fetches from.
        assertThatThrownBy(() -> ingestionService.ingest(fetchedBefore, "ingrid"))
                .isInstanceOf(MarketplaceUrlChangedException.class);

        assertThat(marketplaceRepository.hasSnapshot(fetchedBefore.id())).isFalse();
        Marketplace after = marketplaceRepository.findById(fetchedBefore.id()).orElseThrow();
        assertThat(after.lastIngestOutcome()).isEqualTo(Marketplace.INGEST_FAILED);
        assertThat(after.lastIngestReason()).contains("changed while it was being ingested");
        try (Repository quarantine = storage.quarantine(name)) {
            assertThat(quarantine.getRefDatabase().getRefsByPrefix("refs/snapshots/"))
                    .as("the pin of the refused snapshot is taken back")
                    .isEmpty();
        }
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066.1"})
    void a_change_waiting_on_a_snapshot_being_recorded_is_refused() throws Exception {
        String name = uniqueName("raceinsertfirst");
        Marketplace marketplace = marketplaceRepository.register(
                name, createUpstream(DEFAULT_MANIFEST).toUri().toString());
        String corrected = createUpstream(DEFAULT_MANIFEST).toUri().toString();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Snapshot> insert = pool.submit(() -> transactionTemplate.execute(status -> {
                Snapshot snapshot = snapshotRepository.create(
                        marketplace.id(),
                        uniqueName("sha"),
                        uniqueName("sha"),
                        Snapshot.HELD,
                        null,
                        "ingrid",
                        null,
                        marketplace.url());
                inserted.countDown();
                await(release);
                return snapshot;
            }));
            assertThat(inserted.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            Future<?> change = pool.submit(() -> registrationService.changeUrl(
                    name, corrected, "root", MarketplaceRegistrationService.Reachability.REFUSE));
            awaitLockWaiter(change);
            release.countDown();

            assertThat(insert.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(() -> change.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        Marketplace after = marketplaceRepository.findById(marketplace.id()).orElseThrow();
        assertThat(after.url()).isEqualTo(marketplace.url());
        assertThat(after.registeredBy()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0066.1"})
    void a_snapshot_waiting_on_a_change_being_made_is_not_recorded() throws Exception {
        String name = uniqueName("racechangefirst");
        Marketplace marketplace = marketplaceRepository.register(
                name, createUpstream(DEFAULT_MANIFEST).toUri().toString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection change = dataSource.getConnection()) {
            change.setAutoCommit(false);
            // What changeUrlBeforeFirstSnapshot holds between its lock and its commit.
            try (PreparedStatement lock =
                            change.prepareStatement("SELECT id FROM marketplaces WHERE id = ? FOR UPDATE");
                    PreparedStatement update =
                            change.prepareStatement("UPDATE marketplaces SET url = ? WHERE id = ?")) {
                lock.setLong(1, marketplace.id());
                lock.executeQuery().close();
                update.setString(1, "file:///corrected/" + name + ".git");
                update.setLong(2, marketplace.id());
                update.executeUpdate();
            }
            Future<Snapshot> insert = pool.submit(() -> snapshotRepository.create(
                    marketplace.id(),
                    uniqueName("sha"),
                    uniqueName("sha"),
                    Snapshot.HELD,
                    null,
                    "ingrid",
                    null,
                    marketplace.url()));
            awaitLockWaiter(insert);
            change.commit();

            assertThatThrownBy(() -> insert.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(MarketplaceUrlChangedException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(marketplaceRepository.hasSnapshot(marketplace.id())).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Until a backend is waiting on a lock, or the patience runs out; polled from a connection of its
     * own, as {@code NameCollisionRaceTests} explains. The waiter finishing first means it never
     * needed the row, which is the failure this is here to report.
     */
    private void awaitLockWaiter(Future<?> waiter) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        try (Connection observer = dataSource.getConnection()) {
            observer.setAutoCommit(true);
            while (Instant.now().isBefore(deadline)) {
                if (waiter.isDone()) {
                    throw new AssertionError("completed without waiting for the marketplace row: " + outcome(waiter));
                }
                try (PreparedStatement query = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                                + " WHERE wait_event_type = 'Lock' AND datname = current_database()");
                        ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= 1) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("nothing waited on the marketplace row within " + PATIENCE);
    }

    private static String outcome(Future<?> done) {
        try {
            return String.valueOf(done.get());
        } catch (Exception e) {
            return e.toString();
        }
    }
}
