package dev.skillsgateway.server.observability;

import dev.skillsgateway.server.mirror.MirrorStatistics;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToDoubleFunction;

/**
 * The read-only forge mirror's divergence, published as meters (GW_0191).
 *
 * <p>The mirror already knew all of this; what it did not have was a way to <em>tell</em> anyone
 * without being asked. As first shipped the only signals were a log line, a ledger row and an
 * administrator endpoint somebody has to think to visit — none of which can raise an alert, and the
 * number that matters most was reachable only by asking for it. This is that number as telemetry.
 *
 * <p>Recorded unconditionally through the auto-configured registry, exactly as
 * {@link GatewayMetrics} and {@link ObjectStoreMetrics} are, so a deployment that turns export on
 * gets these without a gateway change. A gateway with no mirror configured publishes them as zeros,
 * which is what a mirror that holds nothing divergent looks like and is the truth.
 *
 * <p><b>{@link #STALE_REFS} is the alert.</b> It counts references the mirror still holds that the
 * facade no longer serves — in the ordinary case, a snapshot the gateway revoked. Non-zero for
 * longer than {@code skills-gateway.mirror.sweep-interval} means the mirror is showing people
 * content the gateway has withdrawn, which is the one way an optional visibility copy can turn into
 * a way around ADR 0008. {@link #SECONDS_SINCE_SUCCESS} is its companion and not a duplicate: a
 * gateway that cannot reach the forge does not know what the mirror holds, so a stale count that is
 * merely old must not be read as a stale count that is low.
 *
 * <p>No meter here carries a marketplace, a commit, a principal or the push credential. That is the
 * same closed-vocabulary rule the rest of the gateway's telemetry keeps: those dimensions live in
 * the adoption endpoints and the audit ledger, where cardinality is a query result rather than a
 * time series — and in this component the credential must not reach a diagnostic surface at all.
 */
public class MirrorMetrics implements MeterBinder {

    /** References the mirror holds that the facade no longer serves. <b>Alert on this.</b> */
    public static final String STALE_REFS = "skills_gateway.mirror.stale_refs";

    /** Served references the mirror lacks or holds at a different commit. */
    public static final String MISSING_REFS = "skills_gateway.mirror.missing_refs";

    /** 1 when the last look at the mirror reached it, 0 when it did not. */
    public static final String REACHABLE = "skills_gateway.mirror.reachable";

    /** Seconds since a reconciliation last succeeded; -1 when none has since startup. */
    public static final String SECONDS_SINCE_SUCCESS = "skills_gateway.mirror.seconds_since_success";

    /** Reconciliations that brought the mirror into line, or found it already there. */
    public static final String RECONCILIATIONS_OK = "skills_gateway.mirror.reconciliations.ok";

    /** Reconciliations that exhausted their retries and left the mirror drifted. */
    public static final String RECONCILIATIONS_FAILED = "skills_gateway.mirror.reconciliations.failed";

    private final MirrorStatistics statistics;

    public MirrorMetrics(MirrorStatistics statistics) {
        this.statistics = statistics;
    }

    @Override
    @Requirements({"GW_0191"})
    public void bindTo(MeterRegistry registry) {
        level(
                registry,
                STALE_REFS,
                "references the mirror holds that are no longer served",
                MirrorStatistics::staleRefs);
        level(registry, MISSING_REFS, "served references the mirror lacks", MirrorStatistics::missingRefs);
        level(registry, REACHABLE, "whether the last look at the mirror reached it", MirrorStatistics::reachable);
        level(
                registry,
                SECONDS_SINCE_SUCCESS,
                "seconds since a reconciliation last succeeded",
                MirrorStatistics::secondsSinceSuccess);
        counter(registry, RECONCILIATIONS_OK, "reconciliations that succeeded", MirrorStatistics::reconciliationsOk);
        counter(
                registry,
                RECONCILIATIONS_FAILED,
                "reconciliations that exhausted their retries",
                MirrorStatistics::reconciliationsFailed);
    }

    private void level(MeterRegistry registry, String name, String description, ToDoubleFunction<MirrorStatistics> v) {
        Gauge.builder(name, statistics, v).description(description).register(registry);
    }

    private void counter(
            MeterRegistry registry, String name, String description, ToDoubleFunction<MirrorStatistics> v) {
        FunctionCounter.builder(name, statistics, v).description(description).register(registry);
    }
}
