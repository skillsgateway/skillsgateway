package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.github.reqstool.annotations.SVCs;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ContentTests extends AbstractGatewayTest {

    private static final String TWO_PLUGIN_MANIFEST = """
            {
              "name": "content-marketplace",
              "owner": {"name": "Test"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "greeting skills"},
                {"name": "review", "source": "./plugins/review", "description": "review skills"}
              ]
            }
            """;

    @Test
    @SVCs({"SVC_GW_0020"})
    void snapshotContentListsPluginsAndTheirSkills() throws Exception {
        Path upstream = createUpstream(TWO_PLUGIN_MANIFEST);
        // hello has one skill from the fixture; add a second plugin with two skills.
        for (String skill : List.of("summarize", "critique")) {
            Path skillFile = upstream.resolve("plugins/review/skills/" + skill + "/SKILL.md");
            Files.createDirectories(skillFile.getParent());
            Files.writeString(skillFile, "# " + skill + "\n");
        }
        addUpstreamCommit(upstream, "add review plugin skills");

        Registered registered = registerAndIngest(uniqueName("corp"), upstream);

        String body = mockMvc.perform(get("/api/snapshots/%d/content"
                                .formatted(registered.snapshot().id()))
                        .with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> plugins = JsonPath.read(body, "$.plugins[*].name");
        assertThat(plugins).containsExactlyInAnyOrder("hello", "review");
        List<String> helloSkills = JsonPath.read(body, "$.plugins[?(@.name == 'hello')].skills[*].name");
        assertThat(helloSkills).containsExactly("hello");
        List<String> reviewSkills = JsonPath.read(body, "$.plugins[?(@.name == 'review')].skills[*].name");
        assertThat(reviewSkills).containsExactlyInAnyOrder("summarize", "critique");
        assertThat((String) JsonPath.read(body, "$.sha"))
                .isEqualTo(registered.snapshot().sha());
    }

    @Test
    @SVCs({"SVC_GW_0021"})
    void forgeMetadataIsCapturedAtRegistrationWhenAvailable() throws Exception {
        HttpServer forge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String json = """
                {"full_name": "acme/skills", "description": "Acme skill marketplace", \
                "updated_at": "2026-08-01T12:00:00Z"}""";
        forge.createContext("/api/v1/repos/acme/skills", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        forge.start();
        try {
            String name = uniqueName("corp");
            String url = "http://127.0.0.1:%d/acme/skills.git"
                    .formatted(forge.getAddress().getPort());
            String created = mockMvc.perform(MockMvcRequestBuilders.post("/api/marketplaces")
                            .with(oidcLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat((String) JsonPath.read(created, "$.forgeProject")).isEqualTo("acme/skills");
            assertThat((String) JsonPath.read(created, "$.description")).isEqualTo("Acme skill marketplace");
            assertThat((String) JsonPath.read(created, "$.forge")).isEqualTo("gitea");
            assertThat((String) JsonPath.read(created, "$.upstreamUpdatedAt")).startsWith("2026-08-01T12:00:00");
        } finally {
            forge.stop(0);
        }
    }
}
