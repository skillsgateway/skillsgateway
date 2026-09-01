package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The vetting orchestrator (GW_0037, GW_0038, GW_0043). The gateway does not vet content itself —
 * it runs the configured connectors in order against the quarantined, SHA-pinned snapshot,
 * normalizes their answers, records them against the snapshot, and aggregates them fail-closed.
 *
 * <p>Three properties are deliberate and load-bearing:
 *
 * <ul>
 *   <li><b>Every connector runs.</b> The chain does not short-circuit on the first failure: a
 *       reviewer deciding on a snapshot should see everything that is wrong with it, and a recorded
 *       run should not depend on which connector happened to be first.
 *   <li><b>A connector cannot be skipped.</b> Anything a connector throws — including an
 *       {@link Error} — and any connector that outruns its time limit becomes an
 *       {@link VerdictState#ERROR} verdict, which blocks. There is no catch-and-continue path.
 *   <li><b>Vetting never changes snapshot state.</b> The snapshot stays held; the run gates the
 *       approval. Keeping the two apart is what lets a later re-vetting pass record a new run
 *       against an approved snapshot without inventing a state transition.
 * </ul>
 */
@Service
public class VettingService {

    private static final Logger log = LoggerFactory.getLogger(VettingService.class);

    private final List<VettingConnector> connectors;
    private final VettingRepository vettingRepository;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final ConnectorToggleService toggleService;
    private final SkillsGatewayProperties.Vetting properties;
    private final ExecutorService executor;

    public VettingService(
            List<VettingConnector> connectors,
            VettingRepository vettingRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            ConnectorToggleService toggleService,
            SkillsGatewayProperties properties) {
        this.connectors = connectors.stream()
                .sorted(Comparator.comparingInt(VettingConnector::order).thenComparing(VettingConnector::name))
                .toList();
        this.vettingRepository = vettingRepository;
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.toggleService = toggleService;
        this.properties = properties.vetting();
        // Daemon threads: a connector that ignores interruption after a timeout must never keep
        // the JVM alive. The abandoned thread is the accepted cost of in-process connectors;
        // process isolation is the sandbox-runner connector, a separate capability.
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "vetting-connector");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The chain as configured, in the order it runs. */
    public List<VettingConnector> connectors() {
        return connectors;
    }

    /**
     * Identity of the chain as configured right now: {@code connector@version} for each connector,
     * in chain order (GW_0049). Stamped on every run so that a changed answer about unchanged
     * content can be attributed to the chain rather than guessed at.
     */
    public String chainIdentity() {
        return connectors.stream()
                .map(connector -> connector.name() + "@" + connector.version())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Runs the chain against a snapshot and records the run. Returns the aggregated outcome; the
     * snapshot's own state is untouched.
     */
    @Requirements({"GW_0037", "GW_0038", "GW_0043"})
    public VettingChain.Outcome vet(Snapshot snapshot, String marketplace) {
        return run(snapshot, marketplace, VettingRepository.TRIGGER_INGESTION).outcome();
    }

    /** One chain run and the id it was recorded under. */
    public record Run(long runId, VettingChain.Outcome outcome) {}

    /**
     * {@link #vet} with the cause of the run made explicit, and the run id handed back so a caller
     * that has to reason about the run it just produced — re-vetting does — can read it back
     * rather than guess which one is latest.
     *
     * <p>Nothing about the vetting itself differs by trigger: the same connectors run over the same
     * pinned content, and the snapshot's state is untouched whatever the answer. What a re-vetting
     * verdict <em>means</em> is decided by {@code RevetService}, not here, so this method stays the
     * one place the chain executes.
     */
    @Requirements({"GW_0037", "GW_0038", "GW_0043", "GW_0049"})
    public Run run(Snapshot snapshot, String marketplace, String trigger) {
        long runId = vettingRepository.startRun(snapshot.id(), trigger, chainIdentity());
        List<VerdictState> states = new ArrayList<>(connectors.size());
        try (QuarantineSnapshot content = open(snapshot, marketplace)) {
            int position = 0;
            for (VettingConnector connector : connectors) {
                // A connector an administrator switched off for this marketplace is skipped, not
                // run, and recorded as a distinct disabled verdict so the disablement is part of
                // the run's evidence rather than a silently shorter chain (GW_0143). The
                // aggregation counts it as neither clearing nor blocking.
                Verdict verdict = toggleService.enabled(connector.name(), snapshot.marketplaceId())
                        ? runGuarded(connector, content)
                        : Verdict.disabled(connector.name(), "for marketplace '" + marketplace + "'");
                vettingRepository.recordVerdict(runId, connector.name(), position++, verdict);
                states.add(verdict.state());
                auditLogger.record(
                        "vetting",
                        marketplace,
                        "vetting-verdict",
                        snapshot.sha(),
                        "%s=%s".formatted(connector.name(), verdict.state().stored()));
            }
        } catch (Exception e) {
            // The content itself could not be opened: nothing was vetted, so nothing clears. The
            // run keeps the blocked outcome it was created with.
            log.warn("vetting chain could not read snapshot {} ({})", snapshot.id(), snapshot.sha(), e);
            vettingRepository.recordVerdict(
                    runId, "snapshot-access", 0, Verdict.error("snapshot-access", String.valueOf(e.getMessage())));
            states.add(VerdictState.ERROR);
        }
        VettingChain.Outcome outcome = VettingChain.aggregate(states);
        vettingRepository.finishRun(runId, outcome);
        auditLogger.record(
                "vetting",
                marketplace,
                "vetting-completed",
                snapshot.sha(),
                "trigger=%s; outcome=%s; connectors=%d; chain=%s"
                        .formatted(trigger, outcome.stored(), states.size(), chainIdentity()));
        webhookService.emit(
                WebhookEvent.SNAPSHOT_VETTED, marketplace, snapshot.id(), snapshot.sha(), snapshot.state(), "vetting");
        return new Run(runId, outcome);
    }

    private QuarantineSnapshot open(Snapshot snapshot, String marketplace) throws java.io.IOException {
        return new QuarantineSnapshot(
                snapshot.id(), marketplace, snapshot.sha(), properties.maxFileBytes(), storage.quarantine(marketplace));
    }

    /**
     * One connector, with both failure modes closed: anything it throws becomes an error verdict,
     * and so does outrunning the configured timeout.
     */
    @Requirements({"GW_0038"})
    private Verdict runGuarded(VettingConnector connector, SnapshotUnderVetting content) {
        Future<Verdict> future = executor.submit(() -> connector.vet(content));
        try {
            Verdict verdict = future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return verdict == null ? Verdict.error(connector.name(), "returned no verdict") : verdict;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("vetting connector '{}' exceeded {}", connector.name(), properties.timeout());
            return Verdict.error(connector.name(), "timed out after " + properties.timeout());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Verdict.error(connector.name(), "interrupted");
        } catch (Exception e) {
            // ExecutionException wraps whatever the connector threw, Throwable included.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("vetting connector '{}' failed", connector.name(), cause);
            return Verdict.error(connector.name(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** The snapshot's latest chain run, or empty when the chain has never run for it. */
    public Optional<VettingRepository.Run> latestRun(long snapshotId) {
        return vettingRepository.latestRun(snapshotId);
    }

    /**
     * One recorded run by id, with its verdicts and findings. Re-vetting reads back the run it just
     * produced by id rather than asking for the latest: "latest" is a race the moment two passes
     * overlap, and the judgement about retracting live content must be made about the run that was
     * actually made, not whichever finished last.
     */
    public Optional<VettingRepository.Run> recordedRun(long runId) {
        return vettingRepository.run(runId);
    }

    /**
     * Whether the chain itself objects to this snapshot, before any waiver is considered
     * (GW_0038). A snapshot with no run at all is blocked: absence of evidence is not evidence of
     * safety.
     *
     * <p>This is the <em>recorded</em> answer, not the one that gates an approval. The gate reads
     * the effective outcome from {@code WaiverService.evaluate}, which layers the waivers active
     * at that instant over this run (GW_0045).
     */
    @Requirements({"GW_0038"})
    public boolean blocked(long snapshotId) {
        return vettingRepository
                .latestRun(snapshotId)
                .map(run -> run.outcome().blocked())
                .orElse(true);
    }
}
