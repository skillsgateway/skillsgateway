package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import java.time.Instant;
import java.util.ArrayList;
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
 * The vetting orchestrator (GW_VETTING_0001, GW_VETTING_0002, GW_VETTING_0006). The gateway does not vet content itself —
 * it runs the configured vetters in order against the quarantined, SHA-pinned snapshot,
 * normalizes their answers, records them against the snapshot, and aggregates them fail-closed.
 *
 * <p>Three properties are deliberate and load-bearing:
 *
 * <ul>
 *   <li><b>Every vetter runs, unless an administrator has said otherwise.</b> The default chain
 *       mode is {@link ChainMode#RUN_ALL}, because a reviewer deciding on a snapshot should see
 *       everything that is wrong with it and a recorded run should not depend on which vetter
 *       happened to be first. Under {@link ChainMode#STOP_AFTER_FAIL} (GW_VETTING_0032) the chain
 *       stops after the first failing verdict and records the rest {@link VerdictState#NOT_REACHED}
 *       — never absent — and such a run is blocked at the gate whatever waivers exist
 *       (GW_VETTING_0032.3), so the shorter chain can never be the cheaper answer.
 *   <li><b>A vetter cannot be skipped.</b> Anything a vetter throws — including an
 *       {@link Error} — and any vetter that outruns its time limit becomes an
 *       {@link VerdictState#ERROR} verdict, which blocks. There is no catch-and-continue path.
 *   <li><b>Vetting never changes snapshot state.</b> The snapshot stays held; the run gates the
 *       approval. Keeping the two apart is what lets a later re-vetting pass record a new run
 *       against an approved snapshot without inventing a state transition.
 * </ul>
 */
@Service
public class VettingService {

    private static final Logger log = LoggerFactory.getLogger(VettingService.class);

    /**
     * The ledger principal of the automated vetting chain (GW_AUDIT_0007). The chain is the gateway's own
     * subsystem acting on its own, not a person, so its entries are typed {@link
     * dev.skillsgateway.server.persistence.ActorType#SYSTEM} — declared here and recognised by
     * {@code AdminAuditLogger}'s system-actor set, the one place a ledger entry's actor kind is
     * decided. Referencing this constant rather than re-spelling {@code "vetting"} makes a rename a
     * compile error instead of a silently mistyped, mis-attributed entry.
     */
    public static final String VETTING_ACTOR = "vetting";

    private final List<Vetter> vetters;
    private final VettingRepository vettingRepository;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final WaiverService waiverService;
    private final VetterToggleService toggleService;
    private final VettingChainSettingsService chainSettings;
    private final SkillsGatewayProperties.Vetting properties;
    private final ExecutorService executor;

    public VettingService(
            List<Vetter> vetters,
            VettingRepository vettingRepository,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            WaiverService waiverService,
            VetterToggleService toggleService,
            VettingChainSettingsService chainSettings,
            SkillsGatewayProperties properties) {
        this.vetters = vetters.stream().sorted(VetterOrder.CONFIGURED).toList();
        this.chainSettings = chainSettings;
        this.vettingRepository = vettingRepository;
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.waiverService = waiverService;
        this.toggleService = toggleService;
        this.properties = properties.vetting();
        // Daemon threads: a vetter that ignores interruption after a timeout must never keep
        // the JVM alive. The abandoned thread is the accepted cost of in-process vetters;
        // process isolation is the sandbox-runner vetter, a separate capability.
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "vetting-vetter");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The chain as configured, in its configured order — no marketplace's settings applied. What a
     * surface that is not about one marketplace shows, and the order every marketplace runs until an
     * administrator arranges one of its own.
     */
    public List<Vetter> vetters() {
        return vetters;
    }

    /**
     * The chain of one marketplace, in the order it runs there (GW_VETTING_0033).
     *
     * <p>Arranged over <em>this</em> service's own vetters rather than over the settings service's,
     * so a chain assembled from a subset of the vetters — which is how the vetting tests isolate one
     * of them — is ordered rather than replaced.
     */
    @Requirements({"GW_VETTING_0033"})
    public List<Vetter> vetters(long marketplaceId) {
        return VetterOrder.resolve(
                vetters, chainSettings.resolveOrder(marketplaceId).override());
    }

    /**
     * Identity of the chain as configured right now, with no marketplace's settings applied. The
     * default-scope answer; production paths stamp {@link #chainIdentity(long)} instead.
     */
    public String chainIdentity() {
        return chainIdentity(vetters, ChainMode.RUN_ALL);
    }

    /**
     * Identity of the chain one marketplace runs right now: {@code vetter@version} for each vetter
     * in the order that marketplace runs them, then the chain mode (GW_VETTING_0012,
     * GW_VETTING_0033.2). Stamped on every run so that a changed answer about unchanged content can
     * be attributed to the chain rather than guessed at — which now has to include the mode and the
     * order, because two runs with identical vetter versions can legitimately produce different
     * verdict sets.
     *
     * <p>The mode is appended rather than prefixed so the string still opens with the chain itself,
     * as every row recorded before the mode existed does.
     */
    @Requirements({"GW_VETTING_0012", "GW_VETTING_0033.2"})
    public String chainIdentity(long marketplaceId) {
        return chainIdentity(
                vetters(marketplaceId), chainSettings.resolveMode(marketplaceId).mode());
    }

    private static String chainIdentity(List<Vetter> ordered, ChainMode mode) {
        return ordered.stream()
                        .map(vetter -> vetter.name() + "@" + vetter.version())
                        .collect(java.util.stream.Collectors.joining(","))
                + ";mode=" + mode.stored();
    }

    /**
     * Runs the chain against a snapshot and records the run. Returns the aggregated outcome; the
     * snapshot's own state is untouched.
     */
    @Requirements({"GW_VETTING_0001", "GW_VETTING_0002", "GW_VETTING_0006"})
    public VettingChain.Outcome vet(Snapshot snapshot, String marketplace) {
        return run(snapshot, marketplace, VettingRepository.TRIGGER_INGESTION).outcome();
    }

    /**
     * The ledger detail of one vetter verdict (GW_VETTING_0006). It leads with {@code vetter=state}
     * so the row is scannable, then carries the finding count and the worst severity present and a
     * reference to the chain run the verdict belongs to — so the ledger is auditable on its own
     * rather than as a pointer back into the vetting tables. For a clean pass with no findings it
     * appends the vetter's coverage statement (GW_VETTING_0023), so a passing row still says what was
     * examined instead of only that nothing was found.
     */
    @Requirements({"GW_VETTING_0006", "GW_VETTING_0022"})
    static String verdictDetail(Vetter vetter, Verdict verdict, long runId) {
        String worst = verdict.findings().stream()
                .map(Finding::severity)
                .max(Severity::compareTo)
                .map(Severity::stored)
                .orElse("none");
        StringBuilder detail = new StringBuilder("%s=%s; findings=%d; worst=%s; run=%d"
                .formatted(
                        vetter.name(),
                        verdict.state().stored(),
                        verdict.findings().size(),
                        worst,
                        runId));
        if (verdict.summary() != null
                && (verdict.findings().isEmpty() || verdict.state() == VerdictState.NOT_REACHED)) {
            detail.append("; ").append(verdict.summary());
        }
        return detail.toString();
    }

    /** One chain run and the id it was recorded under. */
    public record Run(long runId, VettingChain.Outcome outcome) {}

    /**
     * {@link #vet} with the cause of the run made explicit, and the run id handed back so a caller
     * that has to reason about the run it just produced — re-vetting does — can read it back
     * rather than guess which one is latest.
     *
     * <p>Nothing about the vetting itself differs by trigger: the same vetters run over the same
     * pinned content, and the snapshot's state is untouched whatever the answer. What a re-vetting
     * verdict <em>means</em> is decided by {@code RevetService}, not here, so this method stays the
     * one place the chain executes.
     */
    @Requirements({
        "GW_VETTING_0001",
        "GW_VETTING_0002",
        "GW_VETTING_0006",
        "GW_VETTING_0012",
        "GW_VETTING_0029.2",
        "GW_VETTING_0032",
        "GW_VETTING_0032.2"
    })
    public Run run(Snapshot snapshot, String marketplace, String trigger) {
        List<Vetter> chain = vetters(snapshot.marketplaceId());
        ChainMode mode = chainSettings.resolveMode(snapshot.marketplaceId()).mode();
        String chainIdentity = chainIdentity(chain, mode);
        long runId = vettingRepository.startRun(snapshot.id(), trigger, chainIdentity);
        // Read once, and only when the mode can actually use them: under run-all nothing consults
        // them here, and the effective outcome reads them again at evaluation time regardless.
        List<Waiver> waivers = mode == ChainMode.STOP_AFTER_FAIL ? waiverService.forSnapshot(snapshot) : List.of();
        List<VerdictState> states = new ArrayList<>(chain.size());
        try (QuarantineSnapshot content = open(snapshot, marketplace)) {
            int position = 0;
            // Set once the chain has stopped, to the vetter whose verdict stopped it; every vetter
            // after that is recorded not reached rather than run (GW_VETTING_0032.2).
            String stoppedBy = null;
            for (Vetter vetter : chain) {
                // A vetter an administrator switched off for this marketplace is skipped, not
                // run, and recorded as a distinct disabled verdict so the disablement is part of
                // the run's evidence rather than a silently shorter chain (GW_VETTING_0029.2). The
                // aggregation counts it as neither clearing nor blocking (GW_VETTING_0029.3).
                //
                // The switch is consulted before the stop: an administrator's standing decision is
                // the older and more informative fact and holds whatever the chain did, so a
                // disabled vetter after the stop still reads disabled rather than not reached.
                Verdict verdict;
                if (!toggleService.enabled(vetter.name(), snapshot.marketplaceId())) {
                    verdict = Verdict.disabled(vetter.name(), "for marketplace '" + marketplace + "'");
                } else if (stoppedBy != null) {
                    verdict = Verdict.notReached(vetter.name(), stoppedBy);
                } else {
                    verdict = runGuarded(vetter, content);
                    // Only a FAIL stops the chain. An ERROR is a fact about the gateway rather than
                    // about the content, and letting a crash silence the rest of the chain would
                    // turn a flaky vetter into a coverage outage; a PENDING has concluded nothing.
                    //
                    // And only a FAIL a reviewer has not already accepted: the chain stops when the
                    // snapshot is already condemned, which a waived finding does not make it.
                    // Without this the same verdict would stop the chain on every later run too,
                    // and the fresh run a waiver exists to enable could never get any further.
                    if (mode == ChainMode.STOP_AFTER_FAIL
                            && verdict.state() == VerdictState.FAIL
                            && WaiverEvaluation.stillObjects(verdict, waivers, snapshot.sha(), Instant.now())) {
                        stoppedBy = vetter.name();
                    }
                }
                vettingRepository.recordVerdict(runId, vetter.name(), position++, verdict);
                states.add(verdict.state());
                auditLogger.record(
                        VETTING_ACTOR,
                        marketplace,
                        "vetting-verdict",
                        snapshot.sha(),
                        verdictDetail(vetter, verdict, runId));
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
                VETTING_ACTOR,
                marketplace,
                "vetting-completed",
                snapshot.sha(),
                "trigger=%s; outcome=%s; vetters=%d; run=%d; chain=%s"
                        .formatted(trigger, outcome.stored(), states.size(), runId, chainIdentity));
        webhookService.emit(
                WebhookEvent.SNAPSHOT_VETTED, marketplace, snapshot.id(), snapshot.sha(), snapshot.state(), "vetting");
        announceIfAwaitingApproval(snapshot, marketplace, runId);
        return new Run(runId, outcome);
    }

    /**
     * The approval-pending announcement (GW_WEBHOOK_0006): a finished chain run over a snapshot that is
     * still held is what "waiting for a person" means concretely, so it is said as its own event
     * rather than left for a receiver to infer from {@code marketplace.snapshot.vetted} — which also fires for
     * runs against approved content.
     *
     * <p>The guard is the snapshot's state as the chain was handed it, which the chain is
     * forbidden to change. An approved or revoked snapshot is announced by its own event.
     *
     * <p>What travels is the <em>effective</em> outcome, the one that gates approval, so a receiver
     * can tell "approve will succeed" from "waive or fix first" without a follow-up call — and
     * nothing beyond counts, vetter names and identifiers (GW_WEBHOOK_0007).
     */
    @Requirements({"GW_WEBHOOK_0006"})
    private void announceIfAwaitingApproval(Snapshot snapshot, String marketplace, long runId) {
        if (!Snapshot.HELD.equals(snapshot.state())) {
            return;
        }
        WaiverEvaluation.Effect effect = waiverService.evaluate(snapshot);
        webhookService.emitApprovalPending(
                marketplace,
                snapshot.id(),
                snapshot.sha(),
                snapshot.state(),
                VETTING_ACTOR,
                new WebhookService.VettingSummary(
                        runId,
                        effect.outcome().name(),
                        effect.recordedOutcome().name(),
                        effect.blockingVetters(),
                        effect.uncovered().size(),
                        effect.suppressions().size()));
    }

    private QuarantineSnapshot open(Snapshot snapshot, String marketplace) throws java.io.IOException {
        return new QuarantineSnapshot(
                snapshot.id(),
                marketplace,
                snapshot.sha(),
                properties.maxFileBytes(),
                properties.contentCacheBytes(),
                storage.quarantine(marketplace));
    }

    /**
     * One vetter, with both failure modes closed: anything it throws becomes an error verdict,
     * and so does outrunning the configured timeout.
     */
    @Requirements({"GW_VETTING_0002"})
    private Verdict runGuarded(Vetter vetter, SnapshotUnderVetting content) {
        Future<Verdict> future = executor.submit(() -> vetter.vet(content));
        try {
            Verdict verdict = future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return verdict == null ? Verdict.error(vetter.name(), "returned no verdict") : verdict;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("vetter '{}' exceeded {}", vetter.name(), properties.timeout());
            return Verdict.error(vetter.name(), "timed out after " + properties.timeout());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Verdict.error(vetter.name(), "interrupted");
        } catch (Exception e) {
            // ExecutionException wraps whatever the vetter threw, Throwable included.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("vetter '{}' failed", vetter.name(), cause);
            return Verdict.error(vetter.name(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
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
     * (GW_VETTING_0002). A snapshot with no run at all is blocked: absence of evidence is not evidence of
     * safety.
     *
     * <p>This is the <em>recorded</em> answer, not the one that gates an approval. The gate reads
     * the effective outcome from {@code WaiverService.evaluate}, which layers the waivers active
     * at that instant over this run (GW_VETTING_0008).
     */
    @Requirements({"GW_VETTING_0002"})
    public boolean blocked(long snapshotId) {
        return vettingRepository
                .latestRun(snapshotId)
                .map(run -> run.outcome().blocked())
                .orElse(true);
    }
}
