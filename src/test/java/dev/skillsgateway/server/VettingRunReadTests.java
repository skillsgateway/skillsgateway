package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.Verdict;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingRepository;
import io.github.reqstool.annotations.SVCs;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reading a chain run back costs the same number of statements whatever the chain length
 * (GW_VETTING_0001), and the findings it returns belong to the verdict that produced them.
 *
 * <p>The two properties are tested together on purpose. A per-verdict findings query is correct
 * and grows with the chain; one grouped query is constant and can group wrongly. Asserting only
 * the statement count would accept a fast wrong answer, and asserting only the content would
 * accept the growth curve this exists to close — so the fixtures deliberately interleave the
 * findings of several verdicts, include a verdict with none, and put a second run alongside whose
 * findings must not leak in.
 */
class VettingRunReadTests extends AbstractGatewayTest {

    /** Long enough that a per-verdict query is unmistakable, and a plausible chain is not. */
    private static final int LONG_CHAIN = 12;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private VettingRepository vettingRepository;

    @Test
    @SVCs({"SVC_GW_VETTING_0001"})
    void reading_a_run_costs_the_same_statements_whatever_the_chain_length() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("nplus1"), "file:///upstream");
        Snapshot snapshot = snapshotRepository.create(marketplace.id(), uniqueName("sha"), Snapshot.HELD, null, null);

        Meter meter = new Meter();
        VettingRepository counted = new VettingRepository(countingClient(meter));

        long shortRun = runWith(snapshot.id(), 1);
        Cost aloneCost = meter.measure(
                () -> assertThat(counted.run(shortRun).orElseThrow().verdicts()).hasSize(1));

        // A second, longer run in the same table. Reading the first one must not notice it.
        long longRun = runWith(snapshot.id(), LONG_CHAIN);
        Cost besideCost = meter.measure(
                () -> assertThat(counted.run(shortRun).orElseThrow().verdicts()).hasSize(1));
        Cost longCost = meter.measure(
                () -> assertThat(counted.run(longRun).orElseThrow().verdicts()).hasSize(LONG_CHAIN));

        // Without this the assertions below are satisfied by 0 == 0, which is what a counter wired
        // to a method the framework never calls reports. It is the control on the instrument.
        assertThat(aloneCost.statements())
                .as("the counter observed the read at all")
                .isGreaterThan(0);
        assertThat(aloneCost.rows()).as("the counter observed rows at all").isGreaterThan(0);

        assertThat(longCost.statements())
                .as(
                        "reading a %d-verdict run must not cost more statements than a 1-verdict run (%d vs %d)",
                        LONG_CHAIN, longCost.statements(), aloneCost.statements())
                .isEqualTo(aloneCost.statements());

        // Statements alone would accept one query that reads every finding in the table and throws
        // most of them away: the grouping would still be right, and the growth curve would still be
        // there — measured in stored data rather than chain length. Rows are what catch that.
        assertThat(besideCost.rows())
                .as(
                        "reading a run must read only its own rows; %d before the longer run existed," + " %d after",
                        aloneCost.rows(), besideCost.rows())
                .isEqualTo(aloneCost.rows());
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0001"})
    void every_finding_reads_back_against_the_verdict_that_produced_it() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("grouping"), "file:///upstream");
        Snapshot snapshot = snapshotRepository.create(marketplace.id(), uniqueName("sha"), Snapshot.HELD, null, null);

        // A second run on the same snapshot, written first so its findings carry the lower ids:
        // a join that forgot to filter by run would fold them into the run under test.
        long otherRun = vettingRepository.startRun(snapshot.id(), VettingRepository.TRIGGER_INGESTION, "other@1");
        vettingRepository.recordVerdict(otherRun, "other-connector", 0, verdict(VerdictState.FAIL, "other", 3));

        long runId = vettingRepository.startRun(snapshot.id(), VettingRepository.TRIGGER_REVET_MANUAL, "test@1");
        // Positions are recorded out of order so the read's ORDER BY is doing the sorting.
        vettingRepository.recordVerdict(runId, "third", 2, verdict(VerdictState.WARN, "c", 2));
        vettingRepository.recordVerdict(runId, "first", 0, verdict(VerdictState.PASS, "a", 3));
        vettingRepository.recordVerdict(runId, "second", 1, verdict(VerdictState.PASS, "b", 0));

        List<VettingRepository.VerdictView> verdicts =
                vettingRepository.run(runId).orElseThrow().verdicts();

        assertThat(verdicts)
                .as("verdicts come back in chain order")
                .extracting(VettingRepository.VerdictView::connector)
                .containsExactly("first", "second", "third");

        assertThat(verdicts.get(0).findings())
                .as("a verdict's findings are its own, in insertion order")
                .extracting(Finding::id)
                .containsExactly("a-0", "a-1", "a-2");
        assertThat(verdicts.get(1).findings())
                .as("a verdict with no findings borrows none")
                .isEmpty();
        assertThat(verdicts.get(2).findings()).extracting(Finding::id).containsExactly("c-0", "c-1");

        assertThat(verdicts)
                .as("nothing from the other run leaks in")
                .flatExtracting(VettingRepository.VerdictView::findings)
                .extracting(Finding::id)
                .doesNotContain("other-0", "other-1", "other-2");

        assertThat(verdicts.get(0).findings())
                .as("every column of a finding survives the grouping")
                .containsExactly(
                        new Finding("a-0", Severity.LOW, "plugins/a/0", "found a-0"),
                        new Finding("a-1", Severity.LOW, "plugins/a/1", "found a-1"),
                        new Finding("a-2", Severity.LOW, "plugins/a/2", "found a-2"));
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0001"})
    void a_run_with_no_verdicts_reads_back_empty() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("emptyrun"), "file:///upstream");
        Snapshot snapshot = snapshotRepository.create(marketplace.id(), uniqueName("sha"), Snapshot.HELD, null, null);
        long runId = vettingRepository.startRun(snapshot.id(), VettingRepository.TRIGGER_INGESTION, "test@1");

        assertThat(vettingRepository.run(runId).orElseThrow().verdicts()).isEmpty();
        assertThat(vettingRepository.latestRun(snapshot.id()).orElseThrow().verdicts())
                .isEmpty();
    }

    private long runWith(long snapshotId, int verdicts) {
        long runId = vettingRepository.startRun(snapshotId, VettingRepository.TRIGGER_INGESTION, "test@1");
        for (int i = 0; i < verdicts; i++) {
            vettingRepository.recordVerdict(runId, "connector-" + i, i, verdict(VerdictState.PASS, "v" + i, 2));
        }
        return runId;
    }

    private static Verdict verdict(VerdictState state, String prefix, int findings) {
        return new Verdict(
                state,
                java.util.stream.IntStream.range(0, findings)
                        .mapToObj(i -> new Finding(
                                prefix + "-" + i,
                                Severity.LOW,
                                "plugins/" + prefix + "/" + i,
                                "found " + prefix + "-" + i))
                        .toList(),
                null,
                "examined " + prefix);
    }

    /** What one read cost: statements prepared, and rows the driver handed back. */
    private record Cost(int statements, int rows) {}

    /** The counters the proxied datasource writes to, and the window that reads them. */
    private static final class Meter {
        private final AtomicInteger statements = new AtomicInteger();
        private final AtomicInteger rows = new AtomicInteger();

        Cost measure(Runnable read) {
            statements.set(0);
            rows.set(0);
            read.run();
            return new Cost(statements.get(), rows.get());
        }
    }

    /**
     * A {@link JdbcClient} over the real datasource that counts the statements it prepares and the
     * rows the driver hands back.
     *
     * <p>The count is taken at the JDBC layer, not inside {@code JdbcTemplate}: the template's
     * internal funnel is a private overload, so an override of the public one is never called and
     * the counter silently reports zero. Proxying {@link Connection}, {@link PreparedStatement} and
     * {@link ResultSet} is independent of how Spring routes a call and cannot fail that way.
     */
    private JdbcClient countingClient(Meter meter) {
        return JdbcClient.create(proxy(DataSource.class, dataSource, meter));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Object target, Meter meter) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            if (type == Connection.class && method.getName().startsWith("prepare")) {
                meter.statements.incrementAndGet();
            }
            Object result = invoke(method, target, args);
            if (type == ResultSet.class && "next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
                meter.rows.incrementAndGet();
            }
            if (result instanceof Connection connection) {
                return proxy(Connection.class, connection, meter);
            }
            if (result instanceof PreparedStatement statement) {
                return proxy(PreparedStatement.class, statement, meter);
            }
            if (result instanceof ResultSet resultSet) {
                return proxy(ResultSet.class, resultSet, meter);
            }
            return result;
        });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
