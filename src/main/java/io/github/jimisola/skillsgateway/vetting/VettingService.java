package io.github.jimisola.skillsgateway.vetting;

import io.github.jimisola.skillsgateway.admin.AdminAuditLogger;
import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.storage.GitStorage;
import io.github.jimisola.skillsgateway.webhook.WebhookEvent;
import io.github.jimisola.skillsgateway.webhook.WebhookService;
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
    private final SkillsGatewayProperties.Vetting properties;
    private final ExecutorService executor;

    public VettingService(
            List<VettingConnector> connectors,
            VettingRepository vettingRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            SkillsGatewayProperties properties) {
        this.connectors = connectors.stream()
                .sorted(Comparator.comparingInt(VettingConnector::order).thenComparing(VettingConnector::name))
                .toList();
        this.vettingRepository = vettingRepository;
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
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
     * Runs the chain against a snapshot and records the run. Returns the aggregated outcome; the
     * snapshot's own state is untouched.
     */
    @Requirements({"GW_0037", "GW_0038", "GW_0043"})
    public VettingChain.Outcome vet(Snapshot snapshot, String marketplace) {
        long runId = vettingRepository.startRun(snapshot.id(), VettingRepository.TRIGGER_INGESTION);
        List<VerdictState> states = new ArrayList<>(connectors.size());
        try (QuarantineSnapshot content = open(snapshot, marketplace)) {
            int position = 0;
            for (VettingConnector connector : connectors) {
                Verdict verdict = runGuarded(connector, content);
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
                "outcome=%s; connectors=%d".formatted(outcome.stored(), states.size()));
        webhookService.emit(
                WebhookEvent.SNAPSHOT_VETTED, marketplace, snapshot.id(), snapshot.sha(), snapshot.state(), "vetting");
        return outcome;
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
