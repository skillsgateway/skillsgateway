package io.github.jimisola.skillsgateway.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of chain runs, their per-connector verdicts, and the findings behind them
 * (GW_0037).
 *
 * <p>Runs are append-only in spirit: a re-vetting pass inserts a new run rather than updating the
 * previous one, so a snapshot's vetting history is the list of its runs. The single mutation is
 * stamping an approval override onto a run (GW_0041).
 */
@Repository
public class VettingRepository {

    /** The only trigger written in v1; scheduled re-vetting will add its own. */
    public static final String TRIGGER_INGESTION = "ingestion";

    private final JdbcClient jdbc;

    public VettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Starts a run, already carrying the fail-closed outcome it has before anything ran. */
    public long startRun(long snapshotId, String trigger) {
        return jdbc.sql("INSERT INTO vetting_runs (snapshot_id, trigger, started_at, outcome)"
                        + " VALUES (:snapshotId, :trigger, :now, :outcome) RETURNING id")
                .param("snapshotId", snapshotId)
                .param("trigger", trigger)
                .param("now", OffsetDateTime.now())
                .param("outcome", VettingChain.Outcome.BLOCKED.stored())
                .query(Long.class)
                .single();
    }

    /** Records one connector's verdict and its findings. */
    @Transactional
    public void recordVerdict(long runId, String connector, int position, Verdict verdict) {
        long verdictId = jdbc.sql(
                        "INSERT INTO vetting_verdicts (run_id, connector, position, state, detail, report_url,"
                                + " created_at) VALUES (:runId, :connector, :position, :state, :detail, :reportUrl,"
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
            jdbc.sql("INSERT INTO vetting_findings (verdict_id, finding_id, severity, location, message)"
                            + " VALUES (:verdictId, :findingId, :severity, :location, :message)")
                    .param("verdictId", verdictId)
                    .param("findingId", finding.id())
                    .param("severity", finding.severity().stored())
                    .param("location", finding.location())
                    .param("message", finding.message())
                    .update();
        }
    }

    /** A one-line summary so the verdict row is readable without joining the findings. */
    private static String detailOf(Verdict verdict) {
        if (verdict.findings().isEmpty()) {
            return null;
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

    public void finishRun(long runId, VettingChain.Outcome outcome) {
        jdbc.sql("UPDATE vetting_runs SET finished_at = :now, outcome = :outcome WHERE id = :id")
                .param("now", OffsetDateTime.now())
                .param("outcome", outcome.stored())
                .param("id", runId)
                .update();
    }

    /**
     * Stamps the reviewer's override onto the run whose outcome blocked the approval (GW_0041).
     * Only a blocked run can carry one, so an override can never be recorded against a chain that
     * did not object.
     */
    public void recordOverride(long runId, String reviewer, String reason) {
        jdbc.sql("UPDATE vetting_runs SET override_by = :reviewer, override_at = :now,"
                        + " override_reason = :reason WHERE id = :id AND outcome = 'blocked'")
                .param("reviewer", reviewer)
                .param("now", OffsetDateTime.now())
                .param("reason", reason)
                .param("id", runId)
                .update();
    }

    /**
     * The snapshot's most recent chain run with its verdicts and findings, or empty when the chain
     * has never run for it — which callers must treat as blocked (GW_0038).
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
                r.overrideBy(),
                r.overrideAt(),
                r.overrideReason(),
                verdicts(r.runId())));
    }

    private List<VerdictView> verdicts(long runId) {
        List<VerdictView> verdicts = jdbc.sql(
                        "SELECT * FROM vetting_verdicts WHERE run_id = :runId ORDER BY position, connector")
                .param("runId", runId)
                .query(VettingRepository::mapVerdict)
                .list();
        List<VerdictView> withFindings = new ArrayList<>(verdicts.size());
        for (VerdictView verdict : verdicts) {
            withFindings.add(new VerdictView(
                    verdict.verdictId(),
                    verdict.connector(),
                    verdict.position(),
                    verdict.state(),
                    verdict.detail(),
                    verdict.reportUrl(),
                    findings(verdict.verdictId())));
        }
        return List.copyOf(withFindings);
    }

    private List<Finding> findings(long verdictId) {
        return jdbc.sql("SELECT * FROM vetting_findings WHERE verdict_id = :verdictId ORDER BY id")
                .param("verdictId", verdictId)
                .query((rs, rowNum) -> new Finding(
                        rs.getString("finding_id"),
                        Severity.of(rs.getString("severity")),
                        rs.getString("location"),
                        rs.getString("message")))
                .list();
    }

    @Schema(description = "One connector's recorded verdict within a chain run")
    public record VerdictView(
            @Schema(description = "Verdict id") long verdictId,
            @Schema(description = "Connector name") String connector,

            @Schema(description = "Position of the connector in the chain")
            int position,

            @Schema(description = "The connector's conclusion")
            VerdictState state,

            @Schema(description = "One-line summary of the findings, or null when there are none")
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
                    allowableValues = {"ingestion"})
            String trigger,

            @Schema(description = "Fail-closed aggregate of the run's verdicts")
            VettingChain.Outcome outcome,

            @Schema(description = "When the run started") Instant startedAt,

            @Schema(description = "When the run finished, or null if it never did")
            Instant finishedAt,

            @Schema(description = "Reviewer who approved despite a blocked outcome, or null")
            String overrideBy,

            @Schema(description = "When the override was recorded, or null")
            Instant overrideAt,

            @Schema(description = "Reason the reviewer gave for the override, or null")
            String overrideReason,

            @Schema(description = "The run's verdicts, in chain order")
            List<VerdictView> verdicts) {

        /** The connectors that are the reason this run blocks; empty when it does not. */
        public List<String> blockingConnectors() {
            return verdicts.stream()
                    .filter(verdict -> !verdict.state().clearing())
                    .map(VerdictView::connector)
                    .toList();
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
                rs.getString("override_by"),
                instant(rs, "override_at"),
                rs.getString("override_reason"),
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
