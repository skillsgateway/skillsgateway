package dev.skillsgateway.server.observability;

import io.github.reqstool.annotations.Requirements;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The one place gateway telemetry names live (GW_0077). Everything here is <em>recorded</em>
 * unconditionally — through the auto-configured registries, which exist whether or not anything
 * exports — so enabling OpenTelemetry export in a deployment ({@code arconia.otel.enabled=true},
 * or the local {@code observability} profile) publishes these without any gateway change. Export
 * stays opt-in; nothing here attempts it.
 *
 * <p>Every tag is a closed vocabulary by construction ({@code outcome}, {@code decision},
 * {@code event}) — never a marketplace, SHA or principal. Those dimensions belong to the adoption
 * API, where cardinality is a query result rather than a time series.
 */
@Component
public class GatewayMetrics {

    /** Timer/span around one ingestion, tagged {@code outcome=success|error}. */
    public static final String INGESTION = "skills_gateway.ingestion";

    /** Timer/span around one approval decision, tagged {@code decision=approve|reject}. */
    public static final String APPROVAL = "skills_gateway.approval";

    /** Counter of facade fetch events, tagged {@code event=info-refs|upload-pack}. */
    public static final String FACADE_FETCHES = "skills_gateway.facade.fetches";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public GatewayMetrics(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    /** Times one ingestion as an observation (a timer always; a span when tracing is on). */
    @Requirements({"GW_0077"})
    public <T> T observeIngestion(Supplier<T> work) {
        return observe(INGESTION, null, null, work);
    }

    /** Times one approval decision; a refusal (e.g. vetting-blocked) is the observation's error. */
    @Requirements({"GW_0077"})
    public <T> T observeApproval(String decision, Supplier<T> work) {
        return observe(APPROVAL, "decision", decision, work);
    }

    /** Counts one facade fetch entry by its event kind; the HTTP span already exists server-side. */
    @Requirements({"GW_0077"})
    public void facadeFetch(String event) {
        meters.counter(FACADE_FETCHES, "event", event).increment();
    }

    /**
     * The one wrapping shape: start, tag the outcome explicitly, record the error, rethrow. The
     * wrapped work's behavior — result or exception — is untouched.
     */
    private <T> T observe(String name, String tagKey, String tagValue, Supplier<T> work) {
        Observation observation = Observation.start(name, observations);
        if (tagKey != null) {
            observation.lowCardinalityKeyValue(tagKey, tagValue);
        }
        try {
            T result = work.get();
            observation.lowCardinalityKeyValue("outcome", "success");
            return result;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", "error");
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
