package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
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
    @SVCs({"SVC_GW_INGEST_0008"})
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

        String body = mockMvc.perform(get("/api/v1/snapshots/%d/content"
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
    @SVCs({"SVC_GW_INGEST_0045"})
    void snapshotContentListsEveryComponentWithEachHooksTrigger() throws Exception {
        String hooks = """
                {"hooks": {"SessionStart": [{"hooks": [
                  {"type": "command", "command": "${CLAUDE_PLUGIN_ROOT}/scripts/start.sh"}]}],
                 "PostToolUse": [{"matcher": "Edit|Write", "hooks": [
                  {"type": "command", "command": "${CLAUDE_PLUGIN_ROOT}/scripts/check.sh"}]}]}}
                """;
        Path upstream = createUpstream(
                TWO_PLUGIN_MANIFEST,
                java.util.Map.of(
                        "plugins/review/commands/review.md", "# review\n",
                        "plugins/review/agents/critic.md", "# critic\n",
                        "plugins/review/agents/editor.md", "# editor\n",
                        "plugins/review/hooks/hooks.json", hooks,
                        "plugins/review/scripts/start.sh", "#!/bin/sh\necho start\n",
                        "plugins/review/scripts/check.sh", "#!/bin/sh\necho check\n",
                        "plugins/review/.mcp.json", "{\"mcpServers\": {\"notes\": {\"command\": \"notes\"}}}",
                        // A malformed declaration elsewhere costs only that plugin's hooks.
                        "plugins/hello/hooks/hooks.json", "{ not json"));

        Registered registered = registerAndIngest(uniqueName("corp"), upstream);

        String body = mockMvc.perform(get("/api/v1/snapshots/%d/content"
                                .formatted(registered.snapshot().id()))
                        .with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String review = "$.plugins[?(@.name == 'review')]";
        assertThat((List<String>) JsonPath.read(body, review + ".commands[*].name"))
                .containsExactly("review");
        assertThat((List<String>) JsonPath.read(body, review + ".agents[*].name"))
                .containsExactlyInAnyOrder("critic", "editor");
        assertThat((List<String>) JsonPath.read(body, review + ".mcpServers[*].name"))
                .containsExactly("notes");
        assertThat((List<String>) JsonPath.read(body, review + ".hooks[*].event"))
                .containsExactly("SessionStart", "PostToolUse");
        assertThat((List<String>) JsonPath.read(body, review + ".hooks[*].matcher"))
                .containsExactly(null, "Edit|Write");
        assertThat((List<String>) JsonPath.read(body, review + ".hooks[*].location"))
                .containsExactly("plugins/review/hooks/hooks.json:2", "plugins/review/hooks/hooks.json:4");
        assertThat((List<String>) JsonPath.read(body, review + ".hooks[*].runs"))
                .containsExactly("${CLAUDE_PLUGIN_ROOT}/scripts/start.sh", "${CLAUDE_PLUGIN_ROOT}/scripts/check.sh");
        String hello = "$.plugins[?(@.name == 'hello')]";
        assertThat((List<String>) JsonPath.read(body, hello + ".skills[*].name"))
                .containsExactly("hello");
        assertThat((List<Object>) JsonPath.read(body, hello + ".hooks[*]")).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0009"})
    void forgeMetadataIsCapturedAtRegistrationWhenAvailable() throws Exception {
        // A forge whose REST API and git service share one origin: registration reads the git
        // service (GW_INGEST_0040) before the forge metadata is captured.
        GitHttpFixture forge = new GitHttpFixture();
        String json = """
                {"full_name": "acme/skills", "description": "Acme skill marketplace", \
                "updated_at": "2026-08-01T12:00:00Z"}""";
        forge.respondJson("/api/v1/repos/acme/skills", json);
        forge.publish("acme/skills", java.util.Map.of(MANIFEST_PATH, DEFAULT_MANIFEST));
        try {
            String name = uniqueName("corp");
            String url = forge.baseUrl() + "/acme/skills.git";
            String created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/marketplaces")
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
            forge.close();
        }
    }
}
