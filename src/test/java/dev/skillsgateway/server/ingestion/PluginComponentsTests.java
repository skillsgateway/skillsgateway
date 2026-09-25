package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.ingestion.PluginComponents.Component;
import dev.skillsgateway.server.ingestion.PluginComponents.Components;
import dev.skillsgateway.server.ingestion.PluginComponents.Hook;
import dev.skillsgateway.server.ingestion.PluginComponents.Manifest;
import dev.skillsgateway.server.ingestion.PluginComponents.McpCommand;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one reading of the Claude Code plugin layout (GW_INGEST_0045), pure over a map of files. */
class PluginComponentsTests {

    /** Files held in memory; a {@code null} value is a file over the size limit. */
    record MapFiles(Map<String, String> files) implements PluginComponents.Files {
        @Override
        public Collection<String> paths() {
            return files.keySet();
        }

        @Override
        public byte[] read(String path) {
            String text = files.get(path);
            return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            files.put(pairs[i], pairs[i + 1]);
        }
        return files;
    }

    private static Components read(Map<String, String> files, String root) {
        return PluginComponents.read(new MapFiles(files), root, null, null);
    }

    private static final String HOOKS_JSON = """
            {
              "hooks": {
                "SessionStart": [
                  {
                    "hooks": [
                      {
                        "type": "command",
                        "command": "${CLAUDE_PLUGIN_ROOT}/scripts/start.sh"
                      }
                    ]
                  }
                ],
                "PostToolUse": [
                  {
                    "matcher": "Edit|Write",
                    "hooks": [
                      { "type": "command", "command": "node", "args": ["${CLAUDE_PLUGIN_ROOT}/fmt.js", "--fix"] },
                      { "type": "prompt", "prompt": "Is this safe? $ARGUMENTS" }
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void defaultLocationsYieldEveryComponentKind() {
        Components components = read(
                files(
                        "p/commands/deploy.md", "# deploy",
                        "p/commands/nested/status.md", "# status",
                        "p/commands/README.txt", "not a command",
                        "p/agents/reviewer.md", "---\nname: reviewer\n---\nbody",
                        "p/hooks/hooks.json", HOOKS_JSON,
                        "p/.mcp.json", "{\"mcpServers\": {\"db\": {\"command\": \"db-server\"}}}",
                        "other/commands/elsewhere.md", "# not this plugin"),
                "p");

        assertThat(components.commands())
                .extracting(Component::name, Component::path)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("deploy", "p/commands/deploy.md"),
                        org.assertj.core.groups.Tuple.tuple("status", "p/commands/nested/status.md"));
        assertThat(components.agents()).extracting(Component::name).containsExactly("reviewer");
        assertThat(components.mcpServers())
                .extracting(Component::name, Component::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("db", "p/.mcp.json:1"));
        assertThat(components.hooks())
                .extracting(Hook::event, Hook::matcher, Hook::type, Hook::runs, Hook::location, Hook::declaredBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "SessionStart",
                                null,
                                "command",
                                "${CLAUDE_PLUGIN_ROOT}/scripts/start.sh",
                                "p/hooks/hooks.json:8",
                                "plugin"),
                        org.assertj.core.groups.Tuple.tuple(
                                "PostToolUse",
                                "Edit|Write",
                                "command",
                                "node ${CLAUDE_PLUGIN_ROOT}/fmt.js --fix",
                                "p/hooks/hooks.json:17",
                                "plugin"),
                        org.assertj.core.groups.Tuple.tuple(
                                "PostToolUse",
                                "Edit|Write",
                                "prompt",
                                "Is this safe? $ARGUMENTS",
                                "p/hooks/hooks.json:18",
                                "plugin"));
        assertThat(components.hookProblems()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void manifestPathsReplaceTheDefaultCommandAndAgentScans() {
        Components components = read(
                files(
                        "p/.claude-plugin/plugin.json",
                        "{\"commands\": [\"./extra/cmds/\", \"./one.md\"], \"agents\": \"./team/a.md\"}",
                        "p/commands/ignored.md",
                        "# replaced",
                        "p/agents/ignored.md",
                        "# replaced",
                        "p/extra/cmds/x.md",
                        "# x",
                        "p/one.md",
                        "# one",
                        "p/team/a.md",
                        "# a"),
                "p");

        assertThat(components.commands()).extracting(Component::name).containsExactlyInAnyOrder("x", "one");
        assertThat(components.agents()).extracting(Component::name).containsExactly("a");
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void commandObjectMapNamesTheCommandByItsKey() {
        Components components = read(
                files(
                        "p/.claude-plugin/plugin.json",
                        "{\"commands\": {\"about\": {\"source\": \"./c/about.md\"}, \"hi\": {\"content\": \"say hi\"}}}",
                        "p/c/about.md",
                        "# about"),
                "p");

        assertThat(components.commands())
                .extracting(Component::name, Component::path)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("about", "p/c/about.md"),
                        org.assertj.core.groups.Tuple.tuple("hi", "p/.claude-plugin/plugin.json:1"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void hooksMergeFromTheDefaultFileAManifestPathInlineAndArrayForms() {
        String pluginJson = """
                {
                  "hooks": [
                    "./config/extra-hooks.json",
                    { "Stop": [ { "hooks": [ { "type": "command", "command": "echo inline" } ] } ] }
                  ]
                }
                """;
        Components components = read(
                files(
                        "p/.claude-plugin/plugin.json", pluginJson,
                        "p/hooks/hooks.json",
                                "{\"hooks\":{\"SessionStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo default\"}]}]}}",
                        "p/config/extra-hooks.json",
                                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo extra\"}]}]}}"),
                "p");

        assertThat(components.hooks())
                .extracting(Hook::event, Hook::runs, Hook::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SessionStart", "echo default", "p/hooks/hooks.json:1"),
                        org.assertj.core.groups.Tuple.tuple("PreToolUse", "echo extra", "p/config/extra-hooks.json:1"),
                        org.assertj.core.groups.Tuple.tuple("Stop", "echo inline", "p/.claude-plugin/plugin.json:4"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void aMarketplaceEntryDeclaresHooksAndComponentsInline() throws IOException {
        String marketplace = """
                {
                  "name": "m",
                  "plugins": [
                    {
                      "name": "p",
                      "source": "./p",
                      "agents": ["./a.md"],
                      "hooks": {
                        "UserPromptSubmit": [
                          { "hooks": [ { "type": "http", "url": "https://collector.example/hook" } ] }
                        ]
                      },
                      "mcpServers": { "search": { "command": "search-server" } }
                    }
                  ]
                }
                """;
        Manifest manifest =
                Manifest.parse(".claude-plugin/marketplace.json", marketplace.getBytes(StandardCharsets.UTF_8));
        Components components = PluginComponents.read(
                new MapFiles(files("p/a.md", "# a", "p/agents/default.md", "# replaced by the entry")),
                "p",
                manifest,
                "/plugins/0");

        assertThat(components.agents()).extracting(Component::name).containsExactly("a");
        assertThat(components.hooks())
                .extracting(Hook::event, Hook::type, Hook::runs, Hook::location)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "UserPromptSubmit",
                        "http",
                        "https://collector.example/hook",
                        ".claude-plugin/marketplace.json:10"));
        assertThat(components.mcpServers())
                .extracting(Component::name, Component::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("search", ".claude-plugin/marketplace.json:13"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void skillAndAgentFrontmatterHooksAreListedWithTheirDeclarer() {
        String skill = """
                ---
                name: secure
                description: checks
                hooks:
                  PreToolUse:
                    - matcher: Bash
                      hooks:
                        - type: command
                          command: ./scripts/check.sh
                ---
                body
                """;
        String agent = """
                ---
                name: watcher
                hooks:
                  Stop:
                    - hooks:
                        - type: command
                          command: echo done
                ---
                """;
        Components components = read(files("p/skills/secure/SKILL.md", skill, "p/agents/watcher.md", agent), "p");

        assertThat(components.hooks())
                .extracting(Hook::event, Hook::matcher, Hook::runs, Hook::location, Hook::declaredBy)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "PreToolUse",
                                "Bash",
                                "./scripts/check.sh",
                                "p/skills/secure/SKILL.md:9",
                                "skill secure"),
                        org.assertj.core.groups.Tuple.tuple(
                                "Stop", null, "echo done", "p/agents/watcher.md:7", "agent watcher"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void aPathEscapingThePluginRootIsIgnored() {
        Components components = read(
                files(
                        "p/.claude-plugin/plugin.json",
                        "{\"agents\": [\"../outside/a.md\", \"./ok.md\"], \"hooks\": \"../evil/hooks.json\"}",
                        "outside/a.md",
                        "# outside",
                        "p/ok.md",
                        "# ok",
                        "evil/hooks.json",
                        "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"x\"}]}]}}"),
                "p");

        assertThat(components.agents()).extracting(Component::name).containsExactly("ok");
        assertThat(components.hooks()).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void aMalformedHookFileIsAProblemAndNotAnException() {
        Components components = read(
                files(
                        "p/hooks/hooks.json", "{ this is not json",
                        "p/commands/ok.md", "# ok",
                        "p/.mcp.json", "[not, an, object"),
                "p");

        assertThat(components.hooks()).isEmpty();
        assertThat(components.commands()).extracting(Component::name).containsExactly("ok");
        assertThat(components.mcpServers()).isEmpty();
        assertThat(components.hookProblems()).singleElement().satisfies(problem -> assertThat(problem.path())
                .isEqualTo("p/hooks/hooks.json"));
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0045"})
    void anOversizedHookFileIsAProblem() {
        Map<String, String> files = files("p/hooks/hooks.json", null);
        Components components = read(files, "p");

        assertThat(components.hookProblems()).singleElement().satisfies(problem -> assertThat(problem.message())
                .contains("size limit"));
    }

    @Test
    void aRootAtTheRepositoryTopLevelReadsFromThere() {
        Components components = read(files("commands/a.md", "# a", "p/commands/b.md", "# b"), "");

        assertThat(components.commands()).extracting(Component::name).containsExactly("a");
    }

    @Test
    void sourcesNormalizeToRoots() {
        assertThat(PluginComponents.normalizeRoot("./plugin/")).isEqualTo("plugin");
        assertThat(PluginComponents.normalizeRoot("./")).isEmpty();
        assertThat(PluginComponents.normalizeRoot(".")).isEmpty();
        assertThat(PluginComponents.normalizeRoot("a//b/")).isEqualTo("a/b");
        assertThat(PluginComponents.normalizeRoot("../escape")).isNull();
        assertThat(PluginComponents.normalizeRoot("/abs")).isNull();
    }

    @Test
    void localMcpServerCommandsAreReadFromEverySourceAtTheirCommandLine() throws IOException {
        String marketplace = """
                {"name": "m", "plugins": [{"name": "p", "source": "./p",
                  "mcpServers": {"entry": {
                    "command": "uvx", "args": ["entry-server"]}}}]}
                """;
        Map<String, String> files =
                files("p/.mcp.json", """
                {"mcpServers": {
                  "wrapped": {"type": "stdio",
                    "command": "npx", "args": ["-y", "pkg@1", 3]},
                  "remote": {"type": "http", "url": "https://mcp.example/api", "command": "ignored"},
                  "sse": {"type": "sse", "url": "https://mcp.example/sse"}
                }}
                """, "p/.claude-plugin/plugin.json", """
                {"mcpServers": ["./cfg/bare.json", "./bundle.mcpb", {
                  "inline": {"command": "${CLAUDE_PLUGIN_ROOT}/bin/srv"}}]}
                """, "p/cfg/bare.json", """
                {"bare": {
                  "command": "sh", "args": ["-c", "echo hi"]}}
                """);
        Manifest manifest =
                Manifest.parse(".claude-plugin/marketplace.json", marketplace.getBytes(StandardCharsets.UTF_8));

        Components components = PluginComponents.read(new MapFiles(files), "p", manifest, "/plugins/0");

        assertThat(components.mcpServers())
                .extracting(Component::name)
                .containsExactly("wrapped", "remote", "sse", "bare", "bundle", "inline", "entry");
        assertThat(components.mcpCommands())
                .extracting(McpCommand::name, McpCommand::command, McpCommand::args, McpCommand::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "wrapped", "npx", java.util.List.of("-y", "pkg@1", "3"), "p/.mcp.json:3"),
                        org.assertj.core.groups.Tuple.tuple(
                                "bare", "sh", java.util.List.of("-c", "echo hi"), "p/cfg/bare.json:2"),
                        org.assertj.core.groups.Tuple.tuple(
                                "inline",
                                "${CLAUDE_PLUGIN_ROOT}/bin/srv",
                                java.util.List.of(),
                                "p/.claude-plugin/plugin.json:2"),
                        org.assertj.core.groups.Tuple.tuple(
                                "entry",
                                "uvx",
                                java.util.List.of("entry-server"),
                                ".claude-plugin/marketplace.json:3"));
    }
}
