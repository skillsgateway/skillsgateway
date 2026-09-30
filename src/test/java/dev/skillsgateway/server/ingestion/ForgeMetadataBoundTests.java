package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An unknown forge is asked the Gitea-shaped question, and whatever answers is read within a
 * bound: a metadata document is a few kilobytes, so an answer past the bound yields no metadata
 * rather than a body held in memory whole.
 */
class ForgeMetadataBoundTests {

    private final AtomicReference<byte[]> answer = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/repos/acme/skills", exchange -> {
            byte[] body = answer.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String cloneUrl() {
        return "http://127.0.0.1:%d/acme/skills.git"
                .formatted(server.getAddress().getPort());
    }

    private void answer(String description) {
        answer.set("{\"full_name\":\"acme/skills\",\"description\":\"%s\"}"
                .formatted(description)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void an_answer_within_the_bound_is_read() {
        answer("skills for everyone");

        assertThat(new ForgeMetadataService().resolve(cloneUrl()))
                .hasValueSatisfying(
                        metadata -> assertThat(metadata.description()).isEqualTo("skills for everyone"));
    }

    @Test
    void an_answer_past_the_bound_yields_no_metadata() {
        answer("x".repeat(ForgeMetadataService.MAX_RESPONSE_BYTES));

        // Mapped to the length so a failure does not print a megabyte of description.
        assertThat(new ForgeMetadataService()
                        .resolve(cloneUrl())
                        .map(m -> m.description().length()))
                .isEmpty();
    }
}
