package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
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
 */
@Service
public class ForgeMirrorService {

    private static final Logger log = LoggerFactory.getLogger(ForgeMirrorService.class);

    /** Ledger actor for mirror updates: the gateway's own outbound integration, not a person. */
    public static final String MIRROR_ACTOR = "forge-mirror";

    /** Ledger event for a mirror brought into line with what is served. */
    public static final String EVENT_UPDATED = "mirror-updated";

    /** Ledger event for a mirror left behind by a push that exhausted its retries. */
    public static final String EVENT_FAILED = "mirror-push-failed";

    private final MirrorTarget target;
    private final GitStorage storage;
    private final AdminAuditLogger auditLogger;
    private final ExecutorService executor;

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<Attempt> lastAttempt = new AtomicReference<>(Attempt.none());

    public ForgeMirrorService(
            SkillsGatewayProperties properties,
            GitStorage storage,
            AdminAuditLogger auditLogger,
            @Qualifier("forgeMirrorExecutor") ExecutorService forgeMirrorExecutor) {
        this.target = MirrorTarget.from(properties);
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.executor = forgeMirrorExecutor;
        if (target != null) {
            log.info("forge mirror enabled for marketplace '{}' at {}", target.marketplace(), target.url());
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
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    reconcileWithRetries(reason);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            pending.decrementAndGet();
            log.warn("forge mirror update for {} was not queued ({}): the mirror will be drifted", marketplace, reason);
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
    @Requirements({"GW_0170", "GW_0171"})
    private void reconcileWithRetries(String reason) {
        for (int attempt = 1; attempt <= target.maxAttempts(); attempt++) {
            try {
                String tip = reconcile();
                lastAttempt.set(new Attempt(Instant.now(), MirrorReport.OK, null));
                auditLogger.record(
                        MIRROR_ACTOR, target.marketplace(), EVENT_UPDATED, tip, "trigger=%s".formatted(reason));
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

    /** Records the failure everywhere it can be seen and returns; the mirror is now drifted. */
    private void give(String reason, Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        lastAttempt.set(new Attempt(Instant.now(), MirrorReport.FAILED, message));
        log.warn(
                "forge mirror for '{}' at {} could not be updated after {} attempts (trigger {}); it is now drifted"
                        + " — what the facade serves is unaffected",
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
     * <p>Deletions are computed only over the served namespaces, so a branch or tag that belongs to
     * the forge repository rather than to the gateway — a README branch, a release tag somebody
     * made — is left alone rather than being taken as drift the gateway should erase.
     */
    @Requirements({"GW_0169", "GW_0171"})
    private String reconcile() throws IOException, URISyntaxException {
        try (Repository published = storage.published(target.marketplace())) {
            Map<String, ObjectId> served = servedRefs(published);
            Map<String, ObjectId> mirrored = mirrorRefs();
            List<RemoteRefUpdate> updates = new ArrayList<>();
            for (Map.Entry<String, ObjectId> ref : served.entrySet()) {
                if (!ref.getValue().equals(mirrored.get(ref.getKey()))) {
                    updates.add(new RemoteRefUpdate(published, ref.getKey(), ref.getKey(), true, null, null));
                }
            }
            for (String ref : mirrored.keySet()) {
                if (GitStorage.isServedRef(ref) && !served.containsKey(ref)) {
                    updates.add(new RemoteRefUpdate(published, (String) null, ref, true, null, null));
                }
            }
            if (!updates.isEmpty()) {
                push(published, updates);
            }
            return name(served.get(GitStorage.SERVED_REF));
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

    /** The mirror's references, as an ls-remote sees them. The only read of the forge there is. */
    private Map<String, ObjectId> mirrorRefs() throws IOException {
        Map<String, ObjectId> refs = new LinkedHashMap<>();
        try {
            Git.lsRemoteRepository()
                    .setRemote(target.url())
                    .setCredentialsProvider(target.credentials())
                    .setTimeout(target.timeoutSeconds())
                    .callAsMap()
                    .forEach((name, ref) -> {
                        if (ref.getObjectId() != null) {
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
