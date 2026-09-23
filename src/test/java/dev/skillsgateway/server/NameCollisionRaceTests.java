package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.NameCollisionException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two approvals of colliding names at the same moment (GW_APPROVAL_0019.3) — the risk ADR 0015 names
 * as the sharpest this design creates.
 *
 * <p>The race is forced rather than hoped for. The test holds a row lock on both snapshots from a
 * connection of its own, so each approval runs every gate — including the name-collision check
 * outside the lock, which both pass, since neither is approved yet — and then stops at the one
 * statement that records it. Only when both are waiting does the test let go. Without a guard both
 * are then recorded; with one, the second to take the approvals' lock re-evaluates after the first
 * has committed and is refused.
 */
class NameCollisionRaceTests extends AbstractNameCollisionTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GitStorage storage;

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.3"})
    void two_colliding_approvals_racing_admit_exactly_one() throws Exception {
        String name = uniquePlugin();
        Registered first = held(name);
        Registered second = held(lookalike(name));
        List<Registered> both = List.of(first, second);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Snapshot>> approvals = new ArrayList<>();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock =
                    blocker.prepareStatement("SELECT id FROM snapshots WHERE id IN (?, ?) FOR NO KEY UPDATE")) {
                lock.setLong(1, first.snapshot().id());
                lock.setLong(2, second.snapshot().id());
                lock.executeQuery().close();
            }
            for (Registered registered : both) {
                approvals.add(pool.submit(() -> approve(registered.snapshot().id())));
            }
            awaitWaiters(blocker, 2);
            blocker.rollback();
        } finally {
            pool.shutdown();
        }
        assertThat(pool.awaitTermination(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        List<Registered> approved = new ArrayList<>();
        List<Registered> refused = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            try {
                approvals.get(i).get();
                approved.add(both.get(i));
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(NameCollisionException.class);
                refused.add(both.get(i));
            }
        }
        assertThat(approved).as("approved").hasSize(1);
        assertThat(refused).as("refused").hasSize(1);

        Registered winner = approved.getFirst();
        Registered loser = refused.getFirst();
        assertThat(state(winner)).isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(winner.marketplace().name())).isPresent();
        assertThat(state(loser)).isEqualTo(Snapshot.HELD);
        assertThat(storage.publishedIfServing(loser.marketplace().name())).isEmpty();
        assertThat(fetchLogRepository.list()).anySatisfy(row -> {
            assertThat(row.get("event")).isEqualTo("snapshot-approval-refused");
            assertThat(row.get("marketplace")).isEqualTo(loser.marketplace().name());
            assertThat(String.valueOf(row.get("detail")))
                    .contains(winner.marketplace().name());
        });
    }

    /** Until {@code count} other backends are waiting on a lock, or the patience runs out. */
    private static void awaitWaiters(Connection connection, int count) throws Exception {
        Instant deadline = Instant.now().plus(PATIENCE);
        int waiting = 0;
        while (Instant.now().isBefore(deadline)) {
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                            + " WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()"
                            + " AND datname = current_database()");
                    ResultSet rows = query.executeQuery()) {
                rows.next();
                waiting = rows.getInt(1);
            }
            if (waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("only %d approvals reached the transition within %s".formatted(waiting, PATIENCE));
    }

    private String state(Registered registered) {
        return snapshotRepository
                .findById(registered.snapshot().id())
                .orElseThrow()
                .state();
    }
}
