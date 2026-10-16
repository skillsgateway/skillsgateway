package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Which time limit the chain applies to which vetter (GW_VETTING_0025): a built-in vetter gets the
 * chain-wide limit, an external connector its own connect and read timeouts. Container-free: the
 * chain runs against a real git repository and a real in-process HTTP endpoint, with a chain-wide
 * limit short enough that every case finishes in a few seconds.
 */
class VettingTimeLimitTests {

    private static final Duration CHAIN_LIMIT = Duration.ofMillis(500);
    private static final long RUN = 7L;

    private final VettingRepository runs = mock(VettingRepository.class);
    private final GitStorage storage = mock(GitStorage.class);

    @TempDir
    Path work;

    private HttpServer endpoint;
    private volatile Duration answerAfter = Duration.ZERO;
    private String sha;

    @BeforeEach
    void start() throws Exception {
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per request, daemon so a hanging answer never outlives the test.
        endpoint.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "time-limit-endpoint");
            thread.setDaemon(true);
            return thread;
        }));
        endpoint.createContext("/vet", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(answerAfter.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] body = "{\"state\":\"pass\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        endpoint.start();
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            git.commit()
                    .setMessage("content")
                    .setAuthor("t", "t@example.com")
                    .setCommitter("t", "t@example.com")
                    .setAllowEmpty(true)
                    .setSign(false)
                    .call();
            sha = git.getRepository().resolve("HEAD").name();
        }
        given(storage.quarantine(anyString()))
                .willAnswer(call -> Git.open(work.toFile()).getRepository());
    }

    @AfterEach
    void stop() {
        endpoint.stop(0);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0062"})
    void a_built_in_vetter_is_cut_off_at_the_chain_wide_limit() {
        Verdict verdict = runAlone(new Sleeper(Duration.ofSeconds(3)));

        assertThat(verdict.state()).isEqualTo(VerdictState.ERROR);
        assertThat(verdict.findings().getFirst().message()).contains("timed out after " + CHAIN_LIMIT);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0062"})
    void an_external_vetter_with_a_longer_read_timeout_outlives_the_chain_wide_limit() {
        answerAfter = Duration.ofMillis(1500);

        Verdict verdict = runAlone(connector(Duration.ofSeconds(5)));

        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0062"})
    void an_external_vetter_without_a_read_timeout_inherits_the_chain_wide_limit() {
        answerAfter = Duration.ofSeconds(30);
        long started = System.nanoTime();

        Verdict verdict = runAlone(connector(null));

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(verdict.state()).isEqualTo(VerdictState.ERROR);
        // Its own HTTP read timeout fired, not the chain's guard around it.
        assertThat(verdict.findings().getFirst().message()).doesNotContain("timed out after PT");
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    private ExternalVettingConnector connector(Duration readTimeout) {
        return new ExternalVettingConnector(
                new ExternalConnectorProperties(
                        "slow-reviewer",
                        URI.create("http://127.0.0.1:" + endpoint.getAddress().getPort() + "/vet"),
                        10,
                        "1",
                        "d",
                        null,
                        null,
                        null,
                        Duration.ofSeconds(2),
                        readTimeout,
                        null,
                        null,
                        null),
                CHAIN_LIMIT);
    }

    private Verdict runAlone(Vetter vetter) {
        service(vetter).run(snapshot(), "m", VettingRepository.TRIGGER_INGESTION);
        ArgumentCaptor<Verdict> verdict = ArgumentCaptor.forClass(Verdict.class);
        verify(runs).recordVerdict(eq(RUN), eq(vetter.name()), eq(0), verdict.capture());
        return verdict.getValue();
    }

    private VettingService service(Vetter vetter) {
        given(runs.startRun(anyLong(), anyString(), anyString())).willReturn(RUN);
        VetterToggleService toggles = mock(VetterToggleService.class);
        given(toggles.enabled(anyString(), anyLong())).willReturn(true);
        VettingChainSettingsService settings = mock(VettingChainSettingsService.class);
        given(settings.resolveOrder(anyLong()))
                .willReturn(new VettingChainSettingsService.OrderResolution(null, null, null));
        given(settings.resolveMode(anyLong()))
                .willReturn(new VettingChainSettingsService.ModeResolution(ChainMode.RUN_ALL, null, null));
        SkillsGatewayProperties properties = mock(SkillsGatewayProperties.class);
        given(properties.vetting())
                .willReturn(new SkillsGatewayProperties.Vetting(
                        CHAIN_LIMIT, null, null, null, null, null, null, null, null));
        return new VettingService(
                List.of(vetter),
                runs,
                storage,
                mock(AdminAuditLogger.class),
                mock(WebhookService.class),
                mock(WaiverService.class),
                toggles,
                settings,
                properties);
    }

    private Snapshot snapshot() {
        return new Snapshot(
                1L,
                2L,
                sha,
                sha,
                Snapshot.APPROVED,
                null,
                Instant.now(),
                "root",
                "root",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Stands in for a built-in: no time limit of its own, so it gets the chain-wide one. */
    private record Sleeper(Duration duration) implements Vetter {
        @Override
        public String name() {
            return "sleeper";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public String description() {
            return "sleeps";
        }

        @Override
        public Verdict vet(SnapshotUnderVetting snapshot) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Verdict.pass();
        }
    }
}
