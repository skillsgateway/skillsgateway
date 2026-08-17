package io.github.jimisola.skillsgateway.sync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncConfig {

    /**
     * Single thread on purpose (GW_0058): a forge expects its delivery answered in seconds, while
     * an upstream fetch can take longer, so triggers are queued rather than run in the request —
     * and one thread means queued triggers for the same marketplace serialize instead of racing.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService syncWebhookExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sync-webhook-ingest");
            thread.setDaemon(true);
            return thread;
        });
    }
}
