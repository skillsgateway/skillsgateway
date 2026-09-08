package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.Requirements;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The optional read-only forge mirror (GW_0169–GW_0172): a copy of exactly what the facade serves,
 * pushed to an external repository so people can browse and search it with the tools a forge is
 * better at.
 *
 * <p><b>It is never a serving surface and never an enforcement path</b> (ADR 0008, GW_0170).
 * Nothing here is consulted by the facade, by authorization or by an approval, and nothing here can
 * fail one: the mirror reacts to a {@code ServedContentChangedEvent} that has already happened, on
 * a thread that is not the one that raised it, and its worst outcome is a recorded failure and a
 * drifted mirror. That is the whole reason the wiring is an event and a queue rather than a call.
 *
 * <p><b>The unit of work is a reconciliation, not a delta.</b> Every task pushes published
 * storage's current served reference set and deletes whatever the mirror still holds outside it,
 * which makes the operation idempotent and self-healing: a lost, duplicated or reordered task
 * cannot corrupt the mirror, and any later approval or revocation repairs whatever an earlier
 * failure left behind. It is also what makes revocation propagate (GW_0171) without a second code
 * path — a snapshot that is no longer served is, by construction, a reference the next
 * reconciliation deletes.
 *
 * <p><b>The gateway's own state is authoritative.</b> A push that exhausts its retries is recorded
 * on the ledger and left as drift for {@link #report()} to surface. It never blocks, delays or
 * reverses the decision that triggered it — a revocation that waited on a forge would let an
 * unreachable forge keep known-bad content on the wire, which inverts the control it was meant to
 * support.
 *
 * <p><b>Drift is bounded, not merely reported</b> (GW_0190). Because a failed push is a designed
 * end state, something has to stop the divergence it leaves from lasting forever:
 * {@code MirrorReconciliationSweep} queues the same reconciliation on a timer, so a revocation
 * whose push gave up, a queue lost to a restart, and a change somebody made on the forge by hand
 * all converge within one interval rather than waiting for the next approval that may never come.
 * That is a security property and not a nicety — a mirror still showing a snapshot the gateway
 * revoked is a way to obtain withdrawn content, which is the bypass ADR 0008 exists to prevent.
 *
 * <p><b>A repair is never silent</b> (GW_0193). A reconciliation records what it changed, and one
 * that changed the mirror when nothing was approved or revoked is recorded under its own event: the
 * same deletion means "the revocation reached the mirror" in one case and "the mirror had diverged"
 * in the other, and automatic repair would otherwise erase the difference. A reconciliation that
 * changed nothing records nothing, so a sweep on a timer cannot bury the ledger.
 */
@Service
public class ForgeMirrorService {

    private static final Logger log = LoggerFactory.getLogger(ForgeMirrorService.class);

    /** Ledger actor for mirror updates: the gateway's own outbound integration, not a person. */
    public static final String MIRROR_ACTOR = "forge-mirror";

    /** Ledger event for a mirror brought into line by a publication or a revocation. */
    public static final String EVENT_UPDATED = "mirror-updated";

    /**
     * Ledger event for a mirror that a reconciliation nothing asked for had to change (GW_0193).
     *
     * <p>Distinct from {@link #EVENT_UPDATED} because the two mean different things. An update
     * following an approval or a revocation is the mirror doing its job. A change made by the sweep
     * or by an administrator's request happened when no content transition had occurred — so
     * whatever it corrected was divergence, left either by an earlier push that gave up or by
     * somebody writing to the forge directly. That is the fact automatic repair would otherwise
     * erase, and it is worth a name.
     */
    public static final String EVENT_REPAIRED = "mirror-drift-repaired";

    /** Ledger event for a mirror left behind by a push that exhausted its retries. */
    public static final String EVENT_FAILED = "mirror-push-failed";

    /**
     * How the drift report's {@code error} opens when the last attempt was a refusal rather than a
     * push that failed: the operator response is different — one points at the forge, the other at
     * the gateway's own storage — and this is where that difference lives, since the outcome value
     * deliberately does not carry it.
     */
    public static final String REFUSAL = "refused: ";

    /**
     * Ledger event for a reconciliation that would not act on what it read (GW_0190).
     *
     * <p>The one place this component declines to be authoritative, and it is the price of deleting
     * automatically. A reconciliation removes whatever the mirror holds that the served set does
     * not, so a read of published storage that <em>succeeds</em> but answers short — a transient
     * backend failure, a half-applied migration, a backend pointed at the wrong prefix — has the
     * honest conclusion "delete everything on the mirror". On a timer that is a silent total wipe
     * filed as a successful repair, which is worse than the staleness the timer exists to bound.
     */
    public static final String EVENT_REFUSED = "mirror-reconciliation-refused";

    /**
     * How many reference names one ledger entry may carry before it falls back to a count. A
     * marketplace with a thousand snapshots must not put a thousand reference names in one row.
     */
    private static final int NAMES_IN_DETAIL = 10;

    private final MirrorTarget target;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final ExecutorService executor;
    private final MarketplaceRepository marketplaces;
    private final SnapshotRepository snapshots;
    private final MirrorStatistics statistics = new MirrorStatistics();

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<Attempt> lastAttempt = new AtomicReference<>(Attempt.none());

    public ForgeMirrorService(
            SkillsGatewayProperties properties,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            MarketplaceRepository marketplaces,
            SnapshotRepository snapshots,
            @Qualifier("forgeMirrorExecutor") ExecutorService forgeMirrorExecutor) {
        this.target = MirrorTarget.from(properties);
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.marketplaces = marketplaces;
        this.snapshots = snapshots;
        this.executor = forgeMirrorExecutor;
        if (target != null) {
            log.info("forge mirror enabled for marketplace '{}' at {}", target.marketplace(), target.url());
        }
    }

    /**
     * Why a reconciliation is running, which is the whole of what separates the two ledger events
     * (GW_0193). A content change is a publication or a revocation announcing itself; a drift check
     * is the sweep or an administrator asking, with no transition behind it — so anything a drift
     * check has to change was divergence.
     */
    public enum Trigger {
        CONTENT_CHANGE(EVENT_UPDATED),
        DRIFT_CHECK(EVENT_REPAIRED);

        private final String event;

        Trigger(String event) {
            this.event = event;
        }

        String event() {
            return event;
        }
    }

    /**
     * What one reconciliation actually changed on the mirror.
     *
     * <p>It exists so the ledger can name it (GW_0193) and so "nothing changed" is a value rather
     * than an inference. That second use is load-bearing once a sweep runs on a timer: a
     * reconciliation that found the mirror already correct must write nothing, or ninety-six rows a
     * day per gateway would drown the two mirror events that mean something.
     */
    private record Reconciliation(String tip, List<String> pushed, List<String> deleted) {

        boolean changed() {
            return !pushed.isEmpty() || !deleted.isEmpty();
        }
    }

    /** The outcome of the most recent mirror update attempt, kept for the drift report. */
    private record Attempt(Instant at, String outcome, String error) {

        static Attempt none() {
            return new Attempt(null, MirrorReport.NONE, null);
        }
    }

    public boolean enabled() {
        return target != null;
    }

    /** Whether the recurring reconciliation should run at all (GW_0190). */
    boolean sweepEnabled() {
        return target != null && target.sweepEnabled();
    }

    /** The counters {@code MirrorMetrics} publishes (GW_0191); nothing else reads them. */
    public MirrorStatistics statistics() {
        return statistics;
    }

    /**
     * Queue a reconciliation of the mirror with what the marketplace now serves.
     *
     * <p>Returns immediately and throws nothing. Both properties are load-bearing: this is called
     * from the thread that just approved or revoked a snapshot, and GW_0170 says that thread's
     * outcome does not depend on the mirror. A marketplace other than the configured one, and a
     * gateway with no mirror at all, do nothing here.
     */
    @Requirements({"GW_0169", "GW_0170", "GW_0171"})
    public void reconcileLater(String marketplace, String reason) {
        if (target == null || !target.marketplace().equals(marketplace)) {
            return;
        }
        queue(reason, Trigger.CONTENT_CHANGE);
    }

    /**
     * Queue a reconciliation that no content transition asked for (GW_0190).
     *
     * <p>This is the sweep's and the administrator's door, and the only difference from
     * {@link #reconcileLater} is the trigger it carries: anything a reconciliation has to change
     * here is divergence, because nothing was approved or revoked. Does nothing, and contacts
     * nothing, when the mirror is disabled.
     */
    @Requirements({"GW_0190"})
    public void checkForDriftLater(String reason) {
        if (target == null) {
            return;
        }
        queue(reason, Trigger.DRIFT_CHECK);
    }

    /**
     * Reconcile now, on an administrator's behalf, and answer with the resulting comparison
     * (GW_0192).
     *
     * <p>This is the one path that waits for the forge, and waiting is the request: an operator who
     * has just fixed an outage or rotated a credential is asking whether the mirror is right
     * <em>now</em>, and a stale answer would defeat it. GW_0170 is untouched — approval, revocation
     * and what the facade serves are none of them on this path — and the wait is bounded by the
     * mirror's own timeout and attempt count, so a forge that has stopped answering costs a
     * request thread and a report that says {@code pendingUpdates} is not zero.
     */
    @Requirements({"GW_0192"})
    public MirrorReport reconcileNow(String reason) {
        if (target == null) {
            return MirrorReport.disabled();
        }
        checkForDriftLater(reason);
        awaitQuiescence(target.attemptBudget());
        return report();
    }

    private void queue(String reason, Trigger trigger) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    reconcileWithRetries(reason, trigger);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            pending.decrementAndGet();
            log.warn(
                    "forge mirror update for {} was not queued ({}): the mirror will be drifted",
                    target.marketplace(),
                    reason);
        }
    }

    /**
     * Wait until no mirror update is queued or in flight.
     *
     * <p>Exists because "the push happened" is otherwise unobservable from outside, which tests
     * need and an operator reading {@link #report()} benefits from — a report taken mid-push would
     * otherwise read as drift. Returns false on timeout rather than throwing.
     */
    public boolean awaitQuiescence(Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Compare what the mirror holds against what the facade serves (GW_0172).
     *
     * <p>Reads both sides now. An unreachable mirror is reported as an unreachable mirror and never
     * as agreement, because a report that fell back to "in sync" on failure would be worse than no
     * report: it would make a mirror nobody could contact look current.
     */
    @Requirements({"GW_0172"})
    public MirrorReport report() {
        if (target == null) {
            return MirrorReport.disabled();
        }
        Attempt attempt = lastAttempt.get();
        Map<String, ObjectId> served;
        try {
            served = servedRefs();
        } catch (IOException e) {
            return unreachable(attempt, null, "could not read published storage: " + e.getMessage());
        }
        String servedTip = name(served.get(GitStorage.SERVED_REF));
        Map<String, ObjectId> mirrored;
        try {
            mirrored = mirrorRefs();
        } catch (RuntimeException | IOException e) {
            return unreachable(attempt, servedTip, "could not read the mirror: " + e.getMessage());
        }
        SortedSet<String> missing = new TreeSet<>();
        served.forEach((ref, id) -> {
            if (!id.equals(mirrored.get(ref))) {
                missing.add(ref);
            }
        });
        SortedSet<String> stale = new TreeSet<>();
        mirrored.forEach((ref, id) -> {
            if (!served.containsKey(ref)) {
                stale.add(ref);
            }
        });
        // An administrator's read is also a look at the mirror, so the gauges learn from it
        // (GW_0191). It never contacts the mirror on their behalf — the report already did.
        statistics.observed(stale.size(), missing.size());
        return new MirrorReport(
                true,
                target.marketplace(),
                target.url(),
                true,
                missing.isEmpty() && stale.isEmpty(),
                servedTip,
                name(mirrored.get(GitStorage.SERVED_REF)),
                List.copyOf(missing),
                List.copyOf(stale),
                pending.get(),
                attempt.at(),
                attempt.outcome(),
                attempt.error());
    }

    private MirrorReport unreachable(Attempt attempt, String servedTip, String error) {
        statistics.unreachable();
        return new MirrorReport(
                true,
                target.marketplace(),
                target.url(),
                false,
                false,
                servedTip,
                null,
                List.of(),
                List.of(),
                pending.get(),
                attempt.at(),
                attempt.outcome(),
                error);
    }

    /**
     * One reconciliation, retried, then given up on as drift.
     *
     * <p>Giving up is a deliberate end state, not a lost update: the next approval or revocation of
     * this marketplace queues another reconciliation that pushes the whole served set again, so a
     * failure costs freshness until then and nothing else. What it must never do is escape — this
     * runs on the mirror's own thread precisely so that there is nothing above it to fail.
     */
    @Requirements({"GW_0170", "GW_0171", "GW_0190", "GW_0193"})
    private void reconcileWithRetries(String reason, Trigger trigger) {
        for (int attempt = 1; attempt <= target.maxAttempts(); attempt++) {
            try {
                Reconciliation done = reconcile();
                lastAttempt.set(new Attempt(Instant.now(), MirrorReport.OK, null));
                statistics.succeeded();
                record(reason, trigger, done);
                return;
            } catch (ImplausibleServedSet incredible) {
                refuse(reason, incredible);
                return;
            } catch (Exception e) {
                if (attempt == target.maxAttempts()) {
                    give(reason, e);
                    return;
                }
                log.debug("forge mirror update failed (attempt {}/{}), retrying", attempt, target.maxAttempts(), e);
                try {
                    Thread.sleep(target.retryDelay().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    give(reason, e);
                    return;
                }
            }
        }
    }

    /**
     * Put on the ledger what this reconciliation changed, or nothing at all (GW_0193).
     *
     * <p>The silence is the deliberate half. Once the sweep runs on a timer, a gateway whose mirror
     * is simply correct would otherwise write a row every interval forever, and the ledger is the
     * product rather than a log file. What is left is two events that each mean something: a mirror
     * following a publication, and a mirror that had drifted and was put back.
     */
    private void record(String reason, Trigger trigger, Reconciliation done) {
        if (!done.changed()) {
            return;
        }
        if (trigger == Trigger.DRIFT_CHECK) {
            log.info(
                    "forge mirror for '{}' had drifted and was repaired ({}): pushed {}, deleted {}",
                    target.marketplace(),
                    reason,
                    done.pushed().size(),
                    done.deleted().size());
        }
        try {
            auditLogger.record(
                    MIRROR_ACTOR,
                    target.marketplace(),
                    trigger.event(),
                    done.tip(),
                    "trigger=%s; pushed=%s; deleted=%s"
                            .formatted(reason, summarize(done.pushed()), summarize(done.deleted())));
        } catch (RuntimeException ledgerFailed) {
            log.error("forge mirror update could not be recorded on the ledger", ledgerFailed);
        }
    }

    /** {@code 0}, or {@code n [names]}, or {@code n [first ten, …]} once there are too many. */
    private static String summarize(List<String> refs) {
        if (refs.isEmpty()) {
            return "0";
        }
        if (refs.size() <= NAMES_IN_DETAIL) {
            return "%d %s".formatted(refs.size(), refs);
        }
        return "%d %s…".formatted(refs.size(), refs.subList(0, NAMES_IN_DETAIL));
    }

    /**
     * Records a reconciliation that declined to act, and returns having changed nothing (GW_0190).
     *
     * <p>Not retried: there is no forge to wait for, and a second read a few milliseconds later
     * would tell the same story. The next scheduled reconciliation is the retry. It is recorded as
     * a failed attempt rather than a successful one for the reason the drift report already fails
     * closed on an unreachable mirror — the gateway did not confirm the mirror is right, so nothing
     * here may read as agreement — and {@code seconds_since_success} therefore keeps climbing,
     * which is the signal an operator alerts on.
     *
     * <p>It reports as {@link MirrorReport#FAILED} rather than as an outcome of its own, with
     * {@link #REFUSAL} opening the error. A fourth outcome value would break every client already
     * reading that field, and would break it in the worst direction: a client that falls through to
     * "fine" on a value it does not recognise would swallow exactly this signal. Under
     * {@code failed}, a client written before this guard existed already treats a refusal as
     * not-ok. The distinction survives where nothing has to parse an enum for it — this error
     * prefix, the log line, and {@link #EVENT_REFUSED} on the ledger.
     */
    private void refuse(String reason, RuntimeException cause) {
        lastAttempt.set(new Attempt(Instant.now(), MirrorReport.FAILED, REFUSAL + cause.getMessage()));
        statistics.failed();
        log.error(
                "forge mirror reconciliation for '{}' refused to act (trigger {}): {}."
                        + " The mirror was left exactly as it was; nothing was pushed and nothing deleted",
                target.marketplace(),
                reason,
                cause.getMessage());
        try {
            auditLogger.record(
                    MIRROR_ACTOR,
                    target.marketplace(),
                    EVENT_REFUSED,
                    null,
                    "trigger=%s; refused: %s".formatted(reason, cause.getMessage()));
        } catch (RuntimeException ledgerFailed) {
            log.error("forge mirror refusal could not be recorded on the ledger", ledgerFailed);
        }
    }

    /** Records the failure everywhere it can be seen and returns; the mirror is now drifted. */
    private void give(String reason, Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        lastAttempt.set(new Attempt(Instant.now(), MirrorReport.FAILED, message));
        statistics.failed();
        statistics.unreachable();
        log.warn(
                "forge mirror for '{}' at {} could not be updated after {} attempts (trigger {}); it is now drifted"
                        + " — what the facade serves is unaffected, and the recurring reconciliation will try again",
                target.marketplace(),
                target.url(),
                target.maxAttempts(),
                reason,
                cause);
        try {
            auditLogger.record(
                    MIRROR_ACTOR,
                    target.marketplace(),
                    EVENT_FAILED,
                    null,
                    "trigger=%s; drifted: %s".formatted(reason, message));
        } catch (RuntimeException ledgerFailed) {
            log.error("forge mirror failure could not be recorded on the ledger", ledgerFailed);
        }
    }

    /**
     * Make the mirror hold exactly the served reference set, and return the served tip.
     *
     * <p>Both sides are read through the same served-reference filter, so a deletion can only ever
     * remove a reference publication itself would have written.
     */
    @Requirements({"GW_0169", "GW_0171", "GW_0191", "GW_0193"})
    private Reconciliation reconcile() throws IOException, URISyntaxException {
        try (Repository published = storage.published(target.marketplace())) {
            Map<String, ObjectId> served = servedRefs(published);
            requireCredible(served);
            Map<String, ObjectId> mirrored = mirrorRefs();
            statistics.observed(
                    (int) mirrored.keySet().stream()
                            .filter(ref -> !served.containsKey(ref))
                            .count(),
                    (int) served.entrySet().stream()
                            .filter(ref -> !ref.getValue().equals(mirrored.get(ref.getKey())))
                            .count());
            List<RemoteRefUpdate> updates = new ArrayList<>();
            SortedSet<String> pushed = new TreeSet<>();
            SortedSet<String> deleted = new TreeSet<>();
            for (Map.Entry<String, ObjectId> ref : served.entrySet()) {
                if (!ref.getValue().equals(mirrored.get(ref.getKey()))) {
                    updates.add(new RemoteRefUpdate(published, ref.getKey(), ref.getKey(), true, null, null));
                    pushed.add(ref.getKey());
                }
            }
            for (String ref : mirrored.keySet()) {
                if (!served.containsKey(ref)) {
                    updates.add(new RemoteRefUpdate(published, (String) null, ref, true, null, null));
                    deleted.add(ref);
                }
            }
            if (!updates.isEmpty()) {
                push(published, updates);
                // The push landed, so the divergence it just closed is gone. Recording it here
                // rather than waiting for the next look is what keeps the gauge from reporting
                // drift the gateway has already repaired.
                statistics.observed(0, 0);
            }
            return new Reconciliation(
                    name(served.get(GitStorage.SERVED_REF)), List.copyOf(pushed), List.copyOf(deleted));
        }
    }

    /**
     * Refuse to act on a served reference set that the gateway's own records contradict (GW_0190).
     *
     * <p>This is the guard that makes automatic deletion safe to run unattended. A reconciliation
     * deletes whatever the mirror holds and the served set does not, so a read of published storage
     * that succeeds but answers short is indistinguishable, from inside the reconciliation, from a
     * marketplace that genuinely stopped serving — and its honest conclusion would be to empty the
     * mirror. Filed as a successful repair, on a timer, that is a worse failure than the staleness
     * the timer exists to bound.
     *
     * <p>The test is against PostgreSQL rather than against a proportion of the mirror, and that
     * choice is the whole of its precision. Publication moves {@code refs/heads/main} and writes
     * {@code refs/snapshots/<sha>} as one all-or-nothing transition, so a marketplace the database
     * still holds live approved snapshots for has a served tip; one whose snapshots have all been
     * revoked has neither, and emptying its mirror is exactly right. A proportional cap would have
     * no threshold separating a legitimate bulk revocation from a degraded read, and refusing the
     * legitimate one would leave revoked content on the mirror — the failure this whole change
     * exists to prevent.
     *
     * <p>The narrow false positive is a reconciliation landing inside the window where a row is
     * already approved and its publication has not yet completed. It fails closed — nothing is
     * deleted, the refusal is recorded, the next reconciliation succeeds — which is the direction
     * this must err in.
     */
    @Requirements({"GW_0190"})
    private void requireCredible(Map<String, ObjectId> served) {
        if (served.containsKey(GitStorage.SERVED_REF)) {
            return;
        }
        int approved = liveApprovedSnapshots();
        if (approved > 0) {
            throw new ImplausibleServedSet(
                    "published storage answered with no served tip for '%s' while %d approved snapshot(s) remain"
                            .formatted(target.marketplace(), approved));
        }
    }

    /** What the gateway's own database says is approved and live — the independent second opinion. */
    private int liveApprovedSnapshots() {
        return marketplaces
                .findByName(target.marketplace())
                .map(marketplace ->
                        snapshots.approvedByMarketplace(marketplace.id()).size())
                .orElse(0);
    }

    /**
     * Published storage answered, and what it answered cannot be true. Its own type because the
     * response is not the response to a forge that is down: there is nothing to retry against and
     * nothing to push, so it short-circuits the attempt loop rather than burning it.
     */
    private static final class ImplausibleServedSet extends RuntimeException {
        ImplausibleServedSet(String message) {
            super(message);
        }
    }

    private void push(Repository published, List<RemoteRefUpdate> updates) throws IOException, URISyntaxException {
        try (Transport transport = Transport.open(published, new URIish(target.url()))) {
            transport.setCredentialsProvider(target.credentials());
            transport.setTimeout(target.timeoutSeconds());
            PushResult result = transport.push(NullProgressMonitor.INSTANCE, updates);
            // JGit reports a refused ref update as a status on the update rather than as an
            // exception, exactly as RefUpdate does locally (GW_0133): a caller that only catches
            // would call a wholly rejected push a success and record a mirror that never moved.
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new IOException("mirror refused %s: %s%s"
                            .formatted(
                                    update.getRemoteName(),
                                    update.getStatus(),
                                    update.getMessage() == null ? "" : " (" + update.getMessage() + ")"));
                }
            }
        }
    }

    private Map<String, ObjectId> servedRefs() throws IOException {
        try (Repository published = storage.published(target.marketplace())) {
            return servedRefs(published);
        }
    }

    /** Exactly what the facade advertises, read from the same predicate the facade filters with. */
    private static Map<String, ObjectId> servedRefs(Repository published) throws IOException {
        Map<String, ObjectId> served = new LinkedHashMap<>();
        for (Ref ref : published.getRefDatabase().getRefs()) {
            ObjectId id = ref.getObjectId();
            if (id != null && GitStorage.isServedRef(ref.getName())) {
                served.put(ref.getName(), id);
            }
        }
        return served;
    }

    /**
     * The mirror's references in the served namespaces, as an ls-remote sees them. The only read of
     * the forge there is.
     *
     * <p>Filtered by the same predicate as the served side, which is what keeps the gateway to its
     * own business: {@code HEAD}, a README branch, a release tag somebody made on the forge are all
     * outside the namespaces publication writes, so they are neither reported as drift nor deleted
     * by a reconciliation.
     */
    private Map<String, ObjectId> mirrorRefs() throws IOException {
        Map<String, ObjectId> refs = new LinkedHashMap<>();
        try {
            Git.lsRemoteRepository()
                    .setRemote(target.url())
                    .setCredentialsProvider(target.credentials())
                    .setTimeout(target.timeoutSeconds())
                    .callAsMap()
                    .forEach((name, ref) -> {
                        if (ref.getObjectId() != null && GitStorage.isServedRef(name)) {
                            refs.put(name, ref.getObjectId());
                        }
                    });
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
        return refs;
    }

    private static String name(ObjectId id) {
        return id == null ? null : id.name();
    }
}
