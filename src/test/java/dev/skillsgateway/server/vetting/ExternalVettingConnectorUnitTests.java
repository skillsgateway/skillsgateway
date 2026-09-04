package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure, container-free verification of {@link ExternalVettingConnector}'s fail-closed contract
 * (GW_0144-GW_0147). Every branch by which a network dependency can fail to produce a verdict the
 * gateway can stand behind is driven against a real in-process HTTP endpoint and asserted to yield
 * an {@link VerdictState#ERROR} verdict, which the chain treats as blocking. No Spring context, no
 * database, no dev-service container — so this is the layer that runs anywhere and pins the trust
 * boundary regardless of the integration harness.
 */
class ExternalVettingConnectorUnitTests {

    private Stub stub;

    @BeforeEach
    void start() throws IOException {
        stub = new Stub();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    // --- Happy paths and the normalization rules -------------------------------------------------

    @Test
    @SVCs({"SVC_GW_0144"})
    void aPassIsMappedToAPassVerdict() {
        stub.respond(200, "{\"state\":\"pass\"}");
        Verdict verdict = connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "clean content")));
        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        // The bundle carried the file's text content, the same view a built-in connector walks.
        assertThat(stub.lastBody()).contains("clean content").contains("\"scanned\":true");
    }

    @Test
    @SVCs({"SVC_GW_0146"})
    void aFailWithFindingsCarriesFindingsAndReportUrl() {
        stub.respond(200, """
                {"state":"fail","reportUrl":"https://r.example/1","findings":[
                  {"id":"exfil","severity":"high","location":"SKILL.md:2","message":"exfiltration"}]}""");
        Verdict verdict = connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x")));
        assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
        assertThat(verdict.reportUrl()).isEqualTo("https://r.example/1");
        assertThat(verdict.findings()).singleElement().satisfies(f -> assertThat(f.id())
                .isEqualTo("exfil"));
    }

    @Test
    @SVCs({"SVC_GW_0146"})
    void aPassAlongsideACriticalFindingIsRecordedAsFailByWorstOf() {
        stub.respond(
                200,
                "{\"state\":\"pass\",\"findings\":[{\"id\":\"backdoor\",\"severity\":\"critical\",\"message\":\"m\"}]}");
        Verdict verdict = connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x")));
        assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
    }

    @Test
    @SVCs({"SVC_GW_0146"})
    void informationalFindingsAloneStillPass() {
        stub.respond(
                200, "{\"state\":\"pass\",\"findings\":[{\"id\":\"note\",\"severity\":\"info\",\"message\":\"m\"}]}");
        Verdict verdict = connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x")));
        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
    }

    @Test
    @SVCs({"SVC_GW_0147"})
    void aPendingAnswerIsRecordedAsPendingWhichBlocks() {
        stub.respond(200, "{\"state\":\"pending\"}");
        Verdict verdict = connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x")));
        assertThat(verdict.state()).isEqualTo(VerdictState.PENDING);
        assertThat(verdict.state().clearing()).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_0144"})
    void theConfiguredCredentialIsSentToTheEndpoint() {
        stub.respond(200, "{\"state\":\"pass\"}");
        ExternalVettingConnector connector = new ExternalVettingConnector(new ExternalConnectorProperties(
                "llm", URI.create(stub.url()), 10, "1", "d", "sekret", null, null, null, null, null, null, null));
        connector.vet(snapshotOf(Map.of("SKILL.md", "x")));
        assertThat(stub.lastHeader("Authorization")).isEqualTo("Bearer sekret");
    }

    // --- Fail-closed: every inconclusive answer blocks (GW_0145) ---------------------------------

    @Test
    @SVCs({"SVC_GW_0145"})
    void aNon2xxStatusIsAnErrorVerdict() {
        stub.respond(503, "unavailable");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anUnreachableEndpointIsAnErrorVerdict() throws IOException {
        assertError(connector(deadUrl()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aReadTimeoutIsAnErrorVerdict() {
        stub.hang(Duration.ofSeconds(3));
        ExternalVettingConnector connector = new ExternalVettingConnector(new ExternalConnectorProperties(
                "llm",
                URI.create(stub.url()),
                10,
                "1",
                "d",
                null,
                null,
                null,
                Duration.ofSeconds(2),
                Duration.ofMillis(250),
                null,
                null,
                null));
        assertError(connector.vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anUnparseableBodyIsAnErrorVerdict() {
        stub.respond(200, "definitely not json");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anEmptyBodyIsAnErrorVerdict() {
        stub.respond(200, "");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aJsonNullBodyIsAnErrorVerdict() {
        stub.respond(200, "null");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anUnrecognizedStateIsAnErrorVerdict() {
        stub.respond(200, "{\"state\":\"approved\"}");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aMissingStateIsAnErrorVerdict() {
        stub.respond(200, "{\"reportUrl\":\"https://r.example/1\"}");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anEndpointDeclaringErrorIsAnErrorVerdict() {
        // 'error' is a gateway-internal state; an endpoint may not declare it as a verdict.
        stub.respond(200, "{\"state\":\"error\"}");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aMalformedFindingSeverityIsAnErrorVerdict() {
        stub.respond(
                200, "{\"state\":\"warn\",\"findings\":[{\"id\":\"x\",\"severity\":\"spicy\",\"message\":\"m\"}]}");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aFindingMissingItsIdIsAnErrorVerdict() {
        stub.respond(200, "{\"state\":\"warn\",\"findings\":[{\"severity\":\"low\",\"message\":\"m\"}]}");
        assertError(connector(stub.url()).vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void anOversizedResponseIsAnErrorVerdict() {
        stub.respond(200, "{\"state\":\"pass\",\"reportUrl\":\"" + "x".repeat(4096) + "\"}");
        ExternalVettingConnector connector = new ExternalVettingConnector(new ExternalConnectorProperties(
                "llm", URI.create(stub.url()), 10, "1", "d", null, null, null, null, null, null, 64L, null));
        assertError(connector.vet(snapshotOf(Map.of("SKILL.md", "x"))));
    }

    @Test
    @SVCs({"SVC_GW_0145"})
    void aSnapshotBundleOverTheRequestCapIsAnErrorVerdict() {
        stub.respond(200, "{\"state\":\"pass\"}");
        ExternalVettingConnector connector = new ExternalVettingConnector(new ExternalConnectorProperties(
                "llm", URI.create(stub.url()), 10, "1", "d", null, null, null, null, null, 8L, null, null));
        // The single file's content exceeds max-request-bytes=8, so no valid bundle can be shipped.
        assertError(connector.vet(snapshotOf(Map.of("SKILL.md", "this content is well over eight bytes"))));
    }

    @Test
    @SVCs({"SVC_GW_0144"})
    void aBinaryFileIsShippedUnscannedNotDropped() {
        stub.respond(200, "{\"state\":\"pass\"}");
        // Invalid UTF-8 makes ContentRules.text return null: present, marked not scanned, not dropped.
        connector(stub.url()).vet(snapshotOfBytes("logo.png", new byte[] {(byte) 0xFF, (byte) 0xFE, 'x'}));
        assertThat(stub.lastBody()).contains("\"path\":\"logo.png\"").contains("\"scanned\":false");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static void assertError(Verdict verdict) {
        assertThat(verdict.state()).isEqualTo(VerdictState.ERROR);
        assertThat(verdict.findings()).extracting(Finding::message).anySatisfy(message -> assertThat(message)
                .contains("produced no verdict"));
    }

    private static ExternalVettingConnector connector(String url) {
        return new ExternalVettingConnector(new ExternalConnectorProperties(
                "llm", URI.create(url), 10, "1", "d", null, null, null, null, null, null, null, null));
    }

    /** A URL whose server has stopped: a deterministic connection refusal. */
    private static String deadUrl() throws IOException {
        HttpServer throwaway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = throwaway.getAddress().getPort();
        throwaway.start();
        throwaway.stop(0);
        return "http://127.0.0.1:" + port + "/vet";
    }

    private static SnapshotUnderVetting snapshotOf(Map<String, String> files) {
        return new SnapshotUnderVetting() {
            @Override
            public long snapshotId() {
                return 1;
            }

            @Override
            public String marketplace() {
                return "m";
            }

            @Override
            public String sha() {
                return "0000000000000000000000000000000000000000";
            }

            @Override
            public void walk(java.util.function.Predicate<String> wanted, FileVisitor visitor) {
                for (Map.Entry<String, String> file : files.entrySet()) {
                    if (wanted.test(file.getKey())) {
                        visitor.visit(file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        };
    }

    private static SnapshotUnderVetting snapshotOfBytes(String path, byte[] content) {
        return new SnapshotUnderVetting() {
            @Override
            public long snapshotId() {
                return 1;
            }

            @Override
            public String marketplace() {
                return "m";
            }

            @Override
            public String sha() {
                return "0000000000000000000000000000000000000000";
            }

            @Override
            public void walk(java.util.function.Predicate<String> wanted, FileVisitor visitor) {
                if (wanted.test(path)) {
                    visitor.visit(path, content);
                }
            }
        };
    }

    /** A single-context in-process HTTP endpoint whose response each test sets. */
    private static final class Stub implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final Map<String, String> lastHeaders = new LinkedHashMap<>();

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/vet", exchange -> {
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Headers headers = exchange.getRequestHeaders();
                synchronized (lastHeaders) {
                    lastHeaders.clear();
                    List<String> names = new ArrayList<>(headers.keySet());
                    for (String name : names) {
                        lastHeaders.put(name, headers.getFirst(name));
                    }
                }
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
                if (bytes.length == 0) {
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                    return;
                }
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
                    // Client already gave up.
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/vet";
        }

        String lastBody() {
            return lastBody.get();
        }

        String lastHeader(String name) {
            synchronized (lastHeaders) {
                return lastHeaders.get(name);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
