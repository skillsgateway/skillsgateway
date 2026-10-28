package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.config.SkillsGatewayProperties.UpstreamCredential;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every request of an upstream listing or fetch stays on the registered URL's scheme, host and port
 * (GW_INGEST_0075), so where the gateway connects is the administrator's choice and never the
 * upstream server's. "elsewhere" stands for any other origin: it must never receive a request.
 */
class UpstreamRedirectTests {

    private HttpServer upstream;
    private HttpServer elsewhere;
    private final Queue<String> upstreamRequests = new ConcurrentLinkedQueue<>();
    private final Queue<String> elsewhereRequests = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startServers() throws IOException {
        elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        elsewhere.createContext("/", exchange -> {
            elsewhereRequests.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        elsewhere.start();
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", exchange -> {
            String request = exchange.getRequestURI().toString();
            upstreamRequests.add(request);
            String suffix = "/info/refs?service=git-upload-pack";
            if (request.startsWith("/away/")) {
                exchange.getResponseHeaders().add("Location", origin(elsewhere) + "/internal/x.git" + suffix);
                exchange.sendResponseHeaders(302, -1);
            } else if (request.startsWith("/renamed/")) {
                // The same server under another host name: another origin all the same.
                exchange.getResponseHeaders()
                        .add(
                                "Location",
                                "http://localhost:" + upstream.getAddress().getPort() + "/internal/x.git" + suffix);
                exchange.sendResponseHeaders(302, -1);
            } else if (request.startsWith("/moved/")) {
                exchange.getResponseHeaders().add("Location", "/landed/m.git" + suffix);
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopServers() {
        upstream.stop(0);
        elsewhere.stop(0);
    }

    private static String origin(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private UpstreamGit anonymous() {
        return new UpstreamGit(new UpstreamCredentials(List.of()));
    }

    private UpstreamGit credentialed() {
        return new UpstreamGit(new UpstreamCredentials(
                List.of(new UpstreamCredential(origin(upstream) + "/away/", "sgw", "tok-away-5d2e81", null))));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0075"})
    void a_listing_redirected_to_another_origin_is_refused_before_it_is_followed() {
        String url = origin(upstream) + "/away/m.git";
        for (UpstreamGit git : List.of(anonymous(), credentialed())) {
            assertThatThrownBy(() -> git.probe(url)).isInstanceOf(UpstreamException.class);
        }

        assertThat(upstreamRequests).isNotEmpty();
        assertThat(elsewhereRequests)
                .as("requests that reached the other origin")
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0075"})
    void a_listing_redirected_to_another_host_name_is_refused_before_it_is_followed() {
        assertThatThrownBy(() -> anonymous().probe(origin(upstream) + "/renamed/m.git"))
                .isInstanceOf(UpstreamException.class);

        assertThat(upstreamRequests).noneSatisfy(request -> assertThat(request).startsWith("/internal/"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0075"})
    void a_fetch_redirected_to_another_origin_is_refused_before_it_is_followed(@TempDir Path dir) throws IOException {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(dir.resolve("q.git").toFile())
                .build()) {
            repo.create(true);
            assertThatThrownBy(() ->
                            anonymous().fetchDefaultBranch(repo, origin(upstream) + "/away/m.git", "refs/heads/q"))
                    .isInstanceOf(UpstreamException.class);
        }

        assertThat(elsewhereRequests)
                .as("requests that reached the other origin")
                .isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0075"})
    void a_redirect_within_the_origin_is_still_followed() {
        assertThatThrownBy(() -> anonymous().probe(origin(upstream) + "/moved/m.git"))
                .isInstanceOf(UpstreamException.class);

        assertThat(upstreamRequests).anySatisfy(request -> assertThat(request).startsWith("/landed/m.git/"));
    }
}
