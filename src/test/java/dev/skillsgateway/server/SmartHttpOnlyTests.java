package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Both git endpoints speak the smart protocol only (GW_FACADE_0002, GW_AUDIT_0001): the served-ref
 * filter, the want policy and the fetch ledger all hang off upload-pack, and a plain-file GET of a
 * repository's files would bypass every one of them.
 */
class SmartHttpOnlyTests extends AbstractGatewayTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private GitStorage storage;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    private HttpResponse<String> get(String endpoint, String repository, String path, String pat) throws Exception {
        String basic = Base64.getEncoder().encodeToString(("token:" + pat).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:%d/%s/%s/%s".formatted(port, endpoint, repository, path)))
                .header("Authorization", "Basic " + basic)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> dumbRoutes(String commitSha) {
        return List.of(
                "info/refs",
                "HEAD",
                "objects/info/packs",
                "objects/%s/%s".formatted(commitSha.substring(0, 2), commitSha.substring(2)));
    }

    private void assertSmartOnly(String endpoint, String repository, String commitSha, String pat) throws Exception {
        for (String path : dumbRoutes(commitSha)) {
            HttpResponse<String> response = get(endpoint, repository, path, pat);
            assertThat(response.statusCode())
                    .as("plain-file GET /%s/%s/%s", endpoint, repository, path)
                    .isGreaterThanOrEqualTo(400);
            assertThat(response.body())
                    .as("body of /%s/%s/%s", endpoint, repository, path)
                    .doesNotContain(commitSha);
        }
        HttpResponse<String> smart = get(endpoint, repository, "info/refs?service=git-upload-pack", pat);
        assertThat(smart.statusCode())
                .as("smart advertisement on /%s/%s", endpoint, repository)
                .isEqualTo(200);
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0036"})
    void the_consumer_facade_serves_no_repository_file_outside_the_smart_protocol() throws Exception {
        String name = uniqueName("smartonly");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        approve(registered.snapshot().id());

        assertSmartOnly("git", name + ".git", registered.snapshot().sha(), newPat());
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0036"})
    void the_publish_endpoint_serves_no_repository_file_outside_the_smart_protocol() throws Exception {
        String name = uniqueName("smartpub");
        marketplaceRepository.register(name, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY, null);
        try (Repository ignored = storage.hosted(name)) {
            // creates the origin
        }
        String pat = tokenService
                .create("alice", "publisher", List.of(), null, List.of(name))
                .token();
        Path working = newWorkDir("smartpub");
        git(working, "init", "--initial-branch=main");
        git(working, "config", "user.email", "publisher@example.com");
        git(working, "config", "user.name", "Publisher");
        Files.createDirectories(working.resolve(".claude-plugin"));
        Files.writeString(working.resolve(MANIFEST_PATH), DEFAULT_MANIFEST, StandardCharsets.UTF_8);
        Files.createDirectories(working.resolve("plugins/hello/skills/hello"));
        Files.writeString(working.resolve("plugins/hello/skills/hello/SKILL.md"), "# Hello skill\n");
        git(working, "add", "-A");
        git(working, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "first-party content");
        GitResult push = git(working, "push", publishUrl(name, pat), "main");
        assertThat(push.exitCode()).as(push.output()).isZero();

        assertSmartOnly("publish", name, headSha(working), pat);
    }
}
