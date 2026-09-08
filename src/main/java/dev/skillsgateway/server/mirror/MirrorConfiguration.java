package dev.skillsgateway.server.mirror;

import dev.skillsgateway.server.observability.MirrorMetrics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MirrorConfiguration {

    /**
     * The mirror's counters as meters (GW_FACADE_0026). Registered here rather than in the service so that
     * the service keeps working with no registry at all — the same split {@code ObjectStoreMetrics}
     * uses — and so the meter names stay in the observability package with every other one.
     */
    @Bean
    public MirrorMetrics mirrorMetrics(ForgeMirrorService mirror) {
        return new MirrorMetrics(mirror.statistics());
    }

    /**
     * The thread the mirror runs on, and the reason approval never waits for a forge (GW_FACADE_0021).
     *
     * <p>Single, like the webhook ingest thread and for the same reason: reconciliations of one
     * marketplace serialize instead of racing, so two of them cannot push conflicting reference
     * sets. Because every reconciliation pushes the current served state rather than a delta, a
     * queue that runs behind converges on the right answer whatever order it drains in — the last
     * one to run is the one that matters.
     *
     * <p>Daemon, so a queued mirror update cannot hold up shutdown of a gateway that is otherwise
     * done: the mirror is a copy, and losing an update to a restart costs freshness until the next
     * approval, which the drift report makes visible in the meantime.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService forgeMirrorExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "forge-mirror");
            thread.setDaemon(true);
            return thread;
        });
    }
}
