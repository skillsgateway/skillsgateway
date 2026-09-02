package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.ExternalConnectorProperties;
import dev.skillsgateway.server.vetting.ExternalVettingConnector;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingConnector;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.VettingService;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verification of the external vetting connector (GW_0144-GW_0147). This connector reaches a network
 * dependency the gateway does not control, so these tests are adversarial where it counts: a hostile
 * or unreachable endpoint, a malformed answer, an oversized answer, and an endpoint that tries to
 * pass content its own findings condemn. The one property under test throughout is fail-closed —
 * every inconclusive answer must block, never silently pass.
 *
 * <p>The connectors run through a real {@link VettingService} over a real ingested snapshot (the
 * {@link #chainOf} helper mirrors the one in {@code VettingTests}), so recording, ordering and the
 * fail-closed aggregate are exercised end to end, against a real in-process HTTP endpoint.
 */
class ExternalVettingConnectorTests extends AbstractGatewayTest {

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private GitStorage storage;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private dev.skillsgateway.server.admin.AdminAuditLogger auditLogger;

    @Autowired
    private SkillsGatewayProperties properties;

    private StubEndpoint endpoint;

    @BeforeEach
    void startEndpoint() throws IOException {
        endpoint = new StubEndpoint();
    }

    @AfterEach
    void stopEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    @Test
    @SVCs({"SVC_GW_0144"})
    void anExternalConnectorPassIsRecordedAndClears() throws Exception {
        endpoint.respond(200, "{\"state\":\"pass\"}");
        String name = uniqueName("extpass");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        VettingChain.Outcome outcome =
                chainOf(connector("llm-review", endpoint.url())).vet(registered.snapshot(), name);

        assertThat(outcome).isEqualTo(VettingChain.Outcome.CLEAR);
        VettingRepository.VerdictView verdict = verdictOf(registered, "llm-review");
        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        assertThat(verdict.position()).isZero();
        // The gateway shipped the snapshot bundle: identity and the skill's text content.
        assertThat(endpoint.lastBody()).contains("\"sha\"").contains("A test skill that says hello");
    }

    @Test
    @SVCs({"SVC_GW_0146"})
    void aFailWithFindingsBlocksAndPersistsFindingsAndReportUrl() throws Exception {
        endpoint.respond(200, """
                {"state":"fail","reportUrl":"https://review.example/report/42","findings":[
                  {"id":"policy-violation","severity":"high","location":"plugins/hello/SKILL.md:3",
                   "message":"the skill exfiltrates the environment"}]}""");
        String name = uniqueName("extfail");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        VettingChain.Outcome outcome =
                chainOf(connector("llm-review", endpoint.url())).vet(registered.snapshot(), name);

        assertThat(outcome).isEqualTo(VettingChain.Outcome.BLOCKED);
        VettingRepository.VerdictView verdict = verdictOf(registered, "llm-review");
        assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
        assertThat(verdict.reportUrl()).isEqualTo("https://review.example/report/42");
        assertThat(verdict.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.id()).isEqualTo("policy-violation");
            assertThat(finding.location()).isEqualTo("plugins/hello/SKILL.md:3");
        });
    }

    /**
     * Worst-of (GW_0146): an endpoint that returns {@code pass} alongside a critical finding cannot
     * pass content its own evidence condemns. The recorded state is the worse of the two.
     */
    @Test
    @SVCs({"SVC_GW_0146"})
    void aPassDeclaredAlongsideACriticalFindingIsRecordedAsFailAndBlocks() throws Exception {
        endpoint.respond(200, """
                {"state":"pass","findings":[
                  {"id":"backdoor","severity":"critical","location":"plugins/hello/SKILL.md:1",
                   "message":"a backdoor was detected"}]}""");
        String name = uniqueName("extworst");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        VettingChain.Outcome outcome =
                chainOf(connector("llm-review", endpoint.url())).vet(registered.snapshot(), name);

        assertThat(outcome).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(verdictOf(registered, "llm-review").state()).isEqualTo(VerdictState.FAIL);
    }

    /**
     * The async seam (GW_0147): a {@code pending} answer is recorded as PENDING, which blocks. It is
     * never a pass, and — like every non-verdict — it gates the approval.
     */
    @Test
    @SVCs({"SVC_GW_0147"})
    void aPendingAnswerBlocksAsTheAsyncSeam() throws Exception {
        endpoint.respond(200, "{\"state\":\"pending\"}");
        String name = uniqueName("extpending");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));

        VettingChain.Outcome outcome =
                chainOf(connector("llm-review", endpoint.url())).vet(registered.snapshot(), name);

        assertThat(outcome).isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(verdictOf(registered, "llm-review").state()).isEqualTo(VerdictState.PENDING);
        // Fail-closed all the way through: the snapshot stays held and cannot be approved.
        assertThat(snapshotRepository
                        .findById(registered.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.HELD);
        assertThatThrownBy(() -> approvalService.approve(registered.snapshot().id(), "alice"))
                .isInstanceOf(VettingBlockedException.class);
    }

    /**
     * Fail-closed, adversarially (GW_0145): every way the call can fail to produce a verdict the
     * gateway can stand behind must record an ERROR and block. None of them may clear.
     */
    @Test
    @SVCs({"SVC_GW_0145"})
    void everyInconclusiveAnswerFromTheEndpointBlocks() throws Exception {
        String name = uniqueName("extclosed");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        long snapshotId = registered.snapshot().id();

        // 1. Unreachable endpoint: a port nothing is listening on (connection refused).
        assertBlockedError(registered, name, connector("llm-review", unreachableUrl()));

        // 2. A non-2xx status.
        endpoint.respond(500, "boom");
        assertBlockedError(registered, name, connector("llm-review", endpoint.url()));

        // 3. A read timeout: the endpoint hangs past the connector's read timeout.
        endpoint.hang(Duration.ofSeconds(5));
        assertBlockedError(registered, name, shortTimeoutConnector("llm-review", endpoint.url()));

        // 4. A body Jackson cannot parse.
        endpoint.respond(200, "this is not json");
        assertBlockedError(registered, name, connector("llm-review", endpoint.url()));

        // 5. A state the gateway does not recognize (an endpoint inventing a pass-like word).
        endpoint.respond(200, "{\"state\":\"approved\"}");
        assertBlockedError(registered, name, connector("llm-review", endpoint.url()));

        // 6. A malformed finding: an unknown severity is not a pass.
        endpoint.respond(
                200, "{\"state\":\"warn\",\"findings\":[{\"id\":\"x\",\"severity\":\"spicy\",\"message\":\"m\"}]}");
        assertBlockedError(registered, name, connector("llm-review", endpoint.url()));

        // 7. An oversized response, larger than max-response-bytes.
        endpoint.respond(200, "{\"state\":\"pass\",\"reportUrl\":\"" + "x".repeat(4096) + "\"}");
        assertBlockedError(registered, name, tinyResponseConnector("llm-review", endpoint.url()));

        // And after all of that, the snapshot is still held and still unapprovable.
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);
        assertThatThrownBy(() -> approvalService.approve(snapshotId, "alice"))
                .isInstanceOf(VettingBlockedException.class);
    }

    private void assertBlockedError(Registered registered, String marketplace, VettingConnector connector) {
        VettingChain.Outcome outcome = chainOf(connector).vet(registered.snapshot(), marketplace);
        assertThat(outcome).as("outcome for %s", connector.description()).isEqualTo(VettingChain.Outcome.BLOCKED);
        VettingRepository.VerdictView verdict = verdictOf(registered, connector.name());
        assertThat(verdict.state()).as("state for %s", connector.description()).isEqualTo(VerdictState.ERROR);
        assertThat(verdict.findings()).extracting(Finding::message).anySatisfy(message -> assertThat(message)
                .contains("produced no verdict"));
    }

    private ExternalVettingConnector connector(String name, String url) {
        return new ExternalVettingConnector(new ExternalConnectorProperties(
                name,
                URI.create(url),
                10,
                "1",
                "external test connector",
                null,
                null,
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                null,
                null,
                null));
    }

    private ExternalVettingConnector shortTimeoutConnector(String name, String url) {
        return new ExternalVettingConnector(new ExternalConnectorProperties(
                name,
                URI.create(url),
                10,
                "1",
                "external test connector (short read timeout)",
                null,
                null,
                null,
                Duration.ofSeconds(2),
                Duration.ofMillis(300),
                null,
                null,
                null));
    }

    private ExternalVettingConnector tinyResponseConnector(String name, String url) {
        return new ExternalVettingConnector(new ExternalConnectorProperties(
                name,
                URI.create(url),
                10,
                "1",
                "external test connector (tiny response cap)",
                null,
                null,
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                null,
                64L,
                null));
    }

    /** A URL whose port had a server that has since stopped: a reliable connection refusal. */
    private String unreachableUrl() throws IOException {
        HttpServer throwaway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = throwaway.getAddress().getPort();
        throwaway.start();
        throwaway.stop(0);
        return "http://127.0.0.1:" + port + "/vet";
    }

    private VettingService chainOf(VettingConnector... connectors) {
        return new VettingService(
                List.of(connectors), vettingRepository, storage, auditLogger, webhookService, properties);
    }

    private VettingRepository.VerdictView verdictOf(Registered registered, String connector) {
        VettingRepository.Run run =
                vettingRepository.latestRun(registered.snapshot().id()).orElseThrow();
        return run.verdicts().stream()
                .filter(candidate -> candidate.connector().equals(connector))
                .findFirst()
                .orElseThrow();
    }

    /** A single-handler in-process HTTP endpoint whose response each test sets. */
    private static final class StubEndpoint implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
        private final AtomicReference<String> lastBody = new AtomicReference<>();

        StubEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/vet", exchange -> {
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                HttpHandler current = handler.get();
                if (current == null) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                current.handle(exchange);
            });
            server.start();
        }

        void respond(int status, String body) {
            handler.set(exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }

        void hang(Duration duration) {
            handler.set(exchange -> {
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                } catch (IOException | RuntimeException ignored) {
                    // The client already gave up; nothing to do.
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/vet";
        }

        String lastBody() {
            return lastBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
