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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reading a chain run back costs the same number of statements whatever the chain length
 * (GW_0037), and the findings it returns belong to the verdict that produced them.
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
    @SVCs({"SVC_GW_0037"})
    void reading_a_run_costs_the_same_statements_whatever_the_chain_length() {
        Marketplace marketplace = marketplaceRepository.register(uniqueName("nplus1"), "file:///upstream");
        Snapshot snapshot = snapshotRepository.create(marketplace.id(), uniqueName("sha"), Snapshot.HELD, null, null);

        long shortRun = runWith(snapshot.id(), 1);
        long longRun = runWith(snapshot.id(), LONG_CHAIN);

        AtomicInteger counter = new AtomicInteger();
        VettingRepository counted = new VettingRepository(countingClient(counter));

        counter.set(0);
        assertThat(counted.run(shortRun).orElseThrow().verdicts()).hasSize(1);
        int forOneVerdict = counter.get();

        counter.set(0);
        assertThat(counted.run(longRun).orElseThrow().verdicts()).hasSize(LONG_CHAIN);
        int forTwelveVerdicts = counter.get();

        // Without this the assertion below is satisfied by 0 == 0, which is what a counter wired
        // to a method the framework never calls reports. It is the control on the instrument.
        assertThat(forOneVerdict)
                .as("the statement counter observed the read at all")
                .isGreaterThan(0);

        assertThat(forTwelveVerdicts)
                .as(
                        "reading a %d-verdict run must not cost more statements than a 1-verdict run" + " (%d vs %d)",
                        LONG_CHAIN, forTwelveVerdicts, forOneVerdict)
                .isEqualTo(forOneVerdict);
    }

    @Test
    @SVCs({"SVC_GW_0037"})
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
    @SVCs({"SVC_GW_0037"})
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

    /**
     * A {@link JdbcClient} over the real datasource that counts the statements it prepares.
     *
     * <p>The count is taken at the JDBC {@link Connection}, not inside {@code JdbcTemplate}: the
     * template's internal funnel is a private overload, so an override of the public one is never
     * called and the counter silently reports zero. Counting {@code prepareStatement} is
     * independent of how Spring routes a call and cannot fail that way.
     */
    private JdbcClient countingClient(AtomicInteger counter) {
        DataSource counting = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(method, dataSource, args);
                    if (result instanceof Connection connection) {
                        return Proxy.newProxyInstance(
                                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (c, m, a) -> {
                                    if (m.getName().startsWith("prepare")
                                            || m.getName().equals("createStatement")) {
                                        counter.incrementAndGet();
                                    }
                                    return invoke(m, connection, a);
                                });
                    }
                    return result;
                });
        return JdbcClient.create(counting);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
