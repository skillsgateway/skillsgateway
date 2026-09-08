package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of chain runs, their per-connector verdicts, and the findings behind them
 * (GW_VETTING_0001).
 *
 * <p>Runs are append-only: a re-vetting pass inserts a new run rather than updating the previous
 * one, so a snapshot's vetting history is the list of its runs, and nothing ever edits what a
 * connector said. Accepting a finding is a waiver ({@link WaiverRepository}) layered over the run
 * at evaluation time, never an edit to the run itself (GW_VETTING_0008).
 */
@Repository
public class VettingRepository {

    /** A run the ingestion of a new snapshot caused. */
    public static final String TRIGGER_INGESTION = "ingestion";

    /** A run the continuous re-vetting sweep caused (GW_VETTING_0012). */
    public static final String TRIGGER_REVET_SCHEDULED = "revet-scheduled";

    /**
     * A run an operator asked for. This is also how a scanner or advisory feed update is turned
     * into fresh evidence today: the built-in connectors have no external feed to subscribe to, so
     * "the feed moved" is an operator calling the re-vet endpoint (GW_VETTING_0012).
     */
    public static final String TRIGGER_REVET_MANUAL = "revet-manual";

    private final JdbcClient jdbc;

    public VettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Starts a run, already carrying the fail-closed outcome it has before anything ran, and
     * stamped with the identity of the chain that is about to produce it.
     */
    @Requirements({"GW_FACADE_0009"})
    public long startRun(long snapshotId, String trigger, String chain) {
        return jdbc.sql("INSERT INTO vetting_runs (snapshot_id, trigger, started_at, outcome, chain)"
                        + " VALUES (:snapshotId, :trigger, :now, :outcome::vetting_run_outcome, :chain) RETURNING id")
                .param("snapshotId", snapshotId)
                .param("trigger", trigger)
                .param("now", OffsetDateTime.now())
                .param("outcome", VettingChain.Outcome.BLOCKED.stored())
                .param("chain", chain)
                .query(Long.class)
                .single();
    }

    /** Records one connector's verdict and its findings. */
    @Requirements({"GW_FACADE_0009"})
    @Transactional
    public void recordVerdict(long runId, String connector, int position, Verdict verdict) {
        long verdictId = jdbc.sql(
                        "INSERT INTO vetting_verdicts (run_id, connector, position, state, detail, report_url,"
                                + " created_at) VALUES (:runId, :connector, :position, :state::vetting_verdict_state, :detail, :reportUrl,"
                                + " :now) RETURNING id")
                .param("runId", runId)
                .param("connector", connector)
                .param("position", position)
                .param("state", verdict.state().stored())
                .param("detail", detailOf(verdict))
                .param("reportUrl", verdict.reportUrl())
                .param("now", OffsetDateTime.now())
                .query(Long.class)
                .single();
        for (Finding finding : verdict.findings()) {
            jdbc.sql(
                            "INSERT INTO vetting_findings (verdict_id, finding_id, severity, location, message)"
                                    + " VALUES (:verdictId, :findingId, :severity::vetting_finding_severity, :location, :message)")
                    .param("verdictId", verdictId)
                    .param("findingId", finding.id())
                    .param("severity", finding.severity().stored())
                    .param("location", finding.location())
                    .param("message", finding.message())
                    .update();
        }
    }

    /**
     * A one-line summary so the verdict row is readable without joining the findings (GW_VETTING_0023).
     * When there are findings it names how many and the worst severity; when there are none it
     * falls back to the connector's coverage summary — what it examined — so a clean pass records
     * substance rather than a null that reads the same as "the connector never ran".
     */
    private static String detailOf(Verdict verdict) {
        if (verdict.findings().isEmpty()) {
            return verdict.summary();
        }
        return "%d finding(s); worst %s"
                .formatted(
                        verdict.findings().size(),
                        verdict.findings().stream()
                                .map(Finding::severity)
                                .max(Severity::compareTo)
                                .orElseThrow()
                                .stored());
    }

    @Requirements({"GW_FACADE_0009"})
    public void finishRun(long runId, VettingChain.Outcome outcome) {
        jdbc.sql("UPDATE vetting_runs SET finished_at = :now, outcome = :outcome::vetting_run_outcome"
                        + " WHERE id = :id")
                .param("now", OffsetDateTime.now())
                .param("outcome", outcome.stored())
                .param("id", runId)
                .update();
    }

    /**
     * The snapshot's most recent chain run with its verdicts and findings, or empty when the chain
     * has never run for it — which callers must treat as blocked (GW_VETTING_0002).
     */
    public Optional<Run> latestRun(long snapshotId) {
        Optional<Run> run = jdbc.sql(
                        "SELECT * FROM vetting_runs WHERE snapshot_id = :snapshotId" + " ORDER BY id DESC LIMIT 1")
                .param("snapshotId", snapshotId)
                .query(VettingRepository::mapRun)
                .optional();
        return run.map(r -> new Run(
                r.runId(),
                r.snapshotId(),
                r.trigger(),
                r.outcome(),
                r.startedAt(),
                r.finishedAt(),
                r.chain(),
                verdicts(r.runId())));
    }

    /** One run by id, with its verdicts and findings — what a just-finished re-vet reads back. */
    public Optional<Run> run(long runId) {
        return jdbc.sql("SELECT * FROM vetting_runs WHERE id = :runId")
                .param("runId", runId)
                .query(VettingRepository::mapRun)
                .optional()
                .map(r -> new Run(
                        r.runId(),
                        r.snapshotId(),
                        r.trigger(),
                        r.outcome(),
                        r.startedAt(),
                        r.finishedAt(),
                        r.chain(),
                        verdicts(r.runId())));
    }

    /**
     * The run's verdicts, each carrying its findings, in two statements whatever the chain length.
     *
     * <p>It used to be one findings query per verdict, so the cost of reading a run grew with the
     * number of connectors in the chain — the one place ADR 0013 conceded an ORM's fetch join would
     * genuinely have helped. The join below is that fetch join, written by hand: every finding of
     * the run in one pass, grouped by verdict in memory. Grouping preserves id order within a
     * verdict, which is the order the per-verdict query returned, and a verdict with no findings
     * keeps an empty list rather than being dropped.
     */
    private List<VerdictView> verdicts(long runId) {
        List<VerdictView> verdicts = jdbc.sql(
                        "SELECT * FROM vetting_verdicts WHERE run_id = :runId ORDER BY position, connector")
                .param("runId", runId)
                .query(VettingRepository::mapVerdict)
                .list();
        if (verdicts.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Finding>> findings = findingsByVerdict(runId);
        return verdicts.stream()
                .map(verdict -> new VerdictView(
                        verdict.verdictId(),
                        verdict.connector(),
                        verdict.position(),
                        verdict.state(),
                        verdict.detail(),
                        verdict.reportUrl(),
                        findings.getOrDefault(verdict.verdictId(), List.of())))
                .toList();
    }

    /** Every finding of every verdict in one run, in one statement, keyed by verdict id. */
    private Map<Long, List<Finding>> findingsByVerdict(long runId) {
        Map<Long, List<Finding>> byVerdict = new HashMap<>();
        jdbc.sql("SELECT f.* FROM vetting_findings f JOIN vetting_verdicts v ON v.id = f.verdict_id"
                        + " WHERE v.run_id = :runId ORDER BY f.id")
                .param("runId", runId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("verdict_id"),
                        new Finding(
                                rs.getString("finding_id"),
                                Severity.of(rs.getString("severity")),
                                rs.getString("location"),
                                rs.getString("message"))))
                .list()
                .forEach(entry -> byVerdict
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byVerdict;
    }

    @Schema(description = "One connector's recorded verdict within a chain run")
    public record VerdictView(
            @Schema(description = "Verdict id") long verdictId,
            @Schema(description = "Connector name") String connector,

            @Schema(description = "Position of the connector in the chain")
            int position,

            @Schema(description = "The connector's conclusion")
            VerdictState state,

            @Schema(
                    description = "One-line summary: how many findings and the worst severity, or — for a clean"
                            + " pass with no findings — what the connector examined")
            String detail,

            @Schema(description = "External report URL, when the connector produced one")
            String reportUrl,

            @Schema(description = "What the connector found")
            List<Finding> findings) {}

    @Schema(description = "One execution of the vetting chain against a snapshot")
    public record Run(
            @Schema(description = "Chain run id") long runId,
            @Schema(description = "Snapshot the run vetted") long snapshotId,

            @Schema(
                    description = "What caused the run",
                    allowableValues = {"ingestion", "revet-scheduled", "revet-manual"})
            String trigger,

            @Schema(description = "Fail-closed aggregate of the run's verdicts")
            VettingChain.Outcome outcome,

            @Schema(description = "When the run started") Instant startedAt,

            @Schema(description = "When the run finished, or null if it never did")
            Instant finishedAt,

            @Schema(
                    description = "Identity of the chain that produced the run: connector@version in chain order",
                    example = "prompt-injection@1,secret-scan@1")
            String chain,

            @Schema(description = "The run's verdicts, in chain order")
            List<VerdictView> verdicts) {

        /** The connectors that are the reason this run blocks; empty when it does not. */
        public List<String> blockingConnectors() {
            return verdicts.stream()
                    .filter(verdict -> !verdict.state().clearing())
                    .map(VerdictView::connector)
                    .toList();
        }

        /** The recorded state of one connector's verdict in this run, or empty if it has none. */
        public java.util.Optional<VerdictState> stateOf(String connector) {
            return verdicts.stream()
                    .filter(verdict -> verdict.connector().equals(connector))
                    .map(VerdictView::state)
                    .findFirst();
        }

        /** Whether this run was produced by re-vetting rather than by ingesting the snapshot. */
        public boolean revet() {
            return TRIGGER_REVET_SCHEDULED.equals(trigger) || TRIGGER_REVET_MANUAL.equals(trigger);
        }
    }

    private static Run mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new Run(
                rs.getLong("id"),
                rs.getLong("snapshot_id"),
                rs.getString("trigger"),
                VettingChain.Outcome.of(rs.getString("outcome")),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getString("chain"),
                List.of());
    }

    private static VerdictView mapVerdict(ResultSet rs, int rowNum) throws SQLException {
        return new VerdictView(
                rs.getLong("id"),
                rs.getString("connector"),
                rs.getInt("position"),
                VerdictState.of(rs.getString("state")),
                rs.getString("detail"),
                rs.getString("report_url"),
                List.of());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
