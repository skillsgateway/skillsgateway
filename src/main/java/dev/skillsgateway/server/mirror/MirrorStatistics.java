package dev.skillsgateway.server.mirror;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the mirror last looked like, kept as plain counters so the mirror needs no meter registry to
 * work (GW_0191). {@code MirrorMetrics} is what turns these into telemetry, and it is the only
 * thing that does — the same split {@code ObjectStoreStatistics} uses, and for the same reason: the
 * meter names stay in one place and this class stays free of a dependency it would only have for
 * observability's sake.
 *
 * <p><b>Everything here is written by a reconciliation or by a report, never by a scrape.</b> A
 * gauge that computed its value on read would put an {@code ls-remote} of a third-party forge on
 * the monitoring system's polling path and would let that system's interval decide how often the
 * gateway talks to it. So the values are as fresh as the last time the gateway looked, and
 * {@link #secondsSinceSuccess()} beside {@link #reachable()} is what makes that staleness itself
 * legible: a gateway that cannot reach the forge at all does not know what the mirror holds, and
 * must not report a stale count as if it did.
 *
 * <p>{@link #staleRefs()} is the one to alert on. It counts references the mirror still holds that
 * the facade no longer serves, which in the ordinary case is a snapshot the gateway revoked — so a
 * non-zero value outstaying a sweep interval means the mirror is showing content the gateway has
 * withdrawn.
 */
public class MirrorStatistics {

    private final AtomicInteger staleRefs = new AtomicInteger();
    private final AtomicInteger missingRefs = new AtomicInteger();
    private final AtomicBoolean reachable = new AtomicBoolean();
    private final AtomicLong lastSuccessNanos = new AtomicLong(-1);
    private final AtomicLong reconciliationsOk = new AtomicLong();
    private final AtomicLong reconciliationsFailed = new AtomicLong();

    /** References the mirror holds that the facade no longer serves. The alert. */
    public int staleRefs() {
        return staleRefs.get();
    }

    /** Served references the mirror lacks or holds at another commit. */
    public int missingRefs() {
        return missingRefs.get();
    }

    /** 1 when the last look at the mirror reached it, 0 when it did not. */
    public int reachable() {
        return reachable.get() ? 1 : 0;
    }

    /**
     * Seconds since a reconciliation last succeeded, or -1 when none has since this gateway started.
     * Climbing without bound is a forge the gateway cannot write to.
     */
    public long secondsSinceSuccess() {
        long at = lastSuccessNanos.get();
        return at < 0 ? -1 : Math.max(0, (System.nanoTime() - at) / 1_000_000_000L);
    }

    /** Reconciliations that brought the mirror into line, or found it already there. */
    public long reconciliationsOk() {
        return reconciliationsOk.get();
    }

    /** Reconciliations that exhausted their retries and left the mirror drifted. */
    public long reconciliationsFailed() {
        return reconciliationsFailed.get();
    }

    /** A look at the mirror that reached it: the counts are now known. */
    void observed(int stale, int missing) {
        staleRefs.set(stale);
        missingRefs.set(missing);
        reachable.set(true);
    }

    /**
     * A look at the mirror that did not reach it. The counts are deliberately left where they were
     * rather than zeroed: zeroing would read as "nothing is stale", which is the fail-open answer,
     * and {@link #reachable()} is what says the numbers beside it are not current.
     */
    void unreachable() {
        reachable.set(false);
    }

    void succeeded() {
        lastSuccessNanos.set(System.nanoTime());
        reconciliationsOk.incrementAndGet();
    }

    void failed() {
        reconciliationsFailed.incrementAndGet();
    }
}
