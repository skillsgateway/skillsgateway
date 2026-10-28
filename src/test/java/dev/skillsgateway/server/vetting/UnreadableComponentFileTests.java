package dev.skillsgateway.server.vetting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A file that can declare MCP or LSP servers, or hooks in skill or agent frontmatter, and that the
 * executable-surface vetter cannot read is reported (GW_VETTING_0066): a file left unread declares
 * nothing the vetter could check, which is not the same as declaring nothing.
 */
class UnreadableComponentFileTests {

    private static final String MANIFEST = "{\"name\": \"m\", \"plugins\": [{\"name\": \"p\", \"source\": \"./p\"}]}";

    /** The manifest plus path/content pairs; a null content is a file over the scan size limit. */
    private static Verdict vet(String... pairs) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(".claude-plugin/marketplace.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < pairs.length; i += 2) {
            files.put(pairs[i], pairs[i + 1] == null ? null : pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return new ExecutableSurfaceVetter().vet(new InMemorySnapshot(files));
    }

    private static List<Finding> unreadable(Verdict verdict) {
        return verdict.findings().stream()
                .filter(finding -> finding.id().equals("hook-config-unreadable"))
                .toList();
    }

    private static void assertReported(Verdict verdict, String path, String what) {
        assertThat(unreadable(verdict)).as(path).singleElement().satisfies(finding -> {
            assertThat(finding.location()).isEqualTo(path);
            assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(finding.message()).contains(what);
        });
        assertThat(verdict.state()).as(path).isNotEqualTo(VerdictState.PASS);
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0069"})
    void an_oversized_server_declaration_file_is_reported() {
        assertReported(vet("p/.mcp.json", null), "p/.mcp.json", "MCP servers");
        assertReported(vet("p/.lsp.json", null), "p/.lsp.json", "LSP servers");
        assertReported(
                vet(
                        "p/.claude-plugin/plugin.json",
                        "{\"name\": \"p\", \"mcpServers\": \"./servers.json\"}",
                        "p/servers.json",
                        null),
                "p/servers.json",
                "MCP servers");
        assertReported(
                vet(
                        "p/.claude-plugin/plugin.json",
                        "{\"name\": \"p\", \"lspServers\": \"./lsp.json\"}",
                        "p/lsp.json",
                        null),
                "p/lsp.json",
                "LSP servers");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0069"})
    void an_unparseable_server_declaration_file_is_reported() {
        assertReported(vet("p/.mcp.json", "{ \"mcpServers\": "), "p/.mcp.json", "MCP servers");
        assertReported(vet("p/.lsp.json", "[1, 2"), "p/.lsp.json", "LSP servers");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0069"})
    void an_oversized_skill_or_agent_file_is_reported() {
        assertReported(vet("p/skills/s/SKILL.md", null), "p/skills/s/SKILL.md", "hooks");
        assertReported(vet("p/agents/a.md", null), "p/agents/a.md", "hooks");
    }

    @Test
    @SVCs({"SVC_GW_VETTING_0069"})
    void readable_files_and_absent_ones_raise_nothing() {
        Verdict verdict = vet(
                "p/.mcp.json", "{\"mcpServers\": {}}",
                "p/.lsp.json", "{}",
                "p/skills/s/SKILL.md", "---\nname: s\ndescription: d\n---\nbody\n",
                "p/agents/a.md", "---\nname: a\n---\nbody\n");

        assertThat(unreadable(verdict)).isEmpty();
        assertThat(unreadable(vet())).isEmpty();
    }
}
