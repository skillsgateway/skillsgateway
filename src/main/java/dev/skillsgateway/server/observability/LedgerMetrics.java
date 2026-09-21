package dev.skillsgateway.server.observability;

import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The audit ledger's size, and how far behind its consumers are (GW_OBSERVABILITY_0003).
 *
 * <p>The ledger is the one table that grows with every client poll, and until now nothing watched
 * it. These two numbers are what turn "the table that ends the deployment" into a line on a chart
 * months before it does.
 *
 * <p>The second matters most in the deployment where the trim (GW_RETENTION_0009) deliberately
 * removes nothing: with no enabled export sink, no entry is eligible and the ledger grows without
 * bound, by design, because the gateway will not delete the only copy of its own evidence. That is
 * a defensible answer only if the operator can see it, so the lag gauge is defined in exactly that
 * case rather than absent — a series nobody can alert on is not an answer.
 *
 * <p>No tags at all, which satisfies the fixed-vocabulary rule the strictest way available.
 * Recorded whether or not anything exports, like every other instrument here.
 */
@Component
public class LedgerMetrics implements MeterBinder {

    private final FetchLogRepository fetchLog;
    private final AuditSinkRepository sinks;

    public LedgerMetrics(FetchLogRepository fetchLog, AuditSinkRepository sinks) {
        this.fetchLog = fetchLog;
        this.sinks = sinks;
    }

    @Override
    @Requirements({"GW_OBSERVABILITY_0003"})
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(GatewayMetrics.LEDGER_DEPTH, fetchLog, repository -> repository.approximateDepth())
                .description("approximate rows in the audit ledger")
                .register(registry);
        Gauge.builder(GatewayMetrics.LEDGER_EXPORT_LAG, this, LedgerMetrics::exportLagSeconds)
                .description("age in seconds of the oldest ledger entry no enabled export sink has taken")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Seconds since the oldest un-exported entry, or zero when there is nothing to report.
     *
     * <p>With no enabled sink the whole ledger is un-exported, so the answer is the age of the
     * oldest entry there is — which is the honest reading of "nothing has taken any of it", and
     * the number that climbs for as long as that stays true.
     */
    @Requirements({"GW_OBSERVABILITY_0003"})
    public double exportLagSeconds() {
        long position = sinks.lowestEnabledCursor()
                .map(AuditSinkRepository.Watermark::position)
                .orElse(0L);
        return fetchLog.oldestAbove(position)
                .map(oldest -> (double) Duration.between(oldest, Instant.now()).toSeconds())
                .orElse(0.0);
    }
}
