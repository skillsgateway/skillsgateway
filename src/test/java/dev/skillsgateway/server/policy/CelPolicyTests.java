package dev.skillsgateway.server.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure CEL core (GW_0089 write-time compilation, GW_0090 bounded fail-closed evaluation). No
 * Spring, no database: exactly the compile/evaluate contract the service and the gate build on.
 */
class CelPolicyTests {

    private static Map<String, Object> facts(List<Map<String, Object>> files, List<Map<String, Object>> skills) {
        return Map.of(
                "snapshot",
                Map.of(
                        "id", 1L,
                        "sha", "0123456789012345678901234567890123456789",
                        "marketplace", "corp",
                        "state", "held"),
                "files",
                files,
                "plugins",
                List.of(Map.of("name", "hello", "description", "d", "source", "./plugins/hello")),
                "skills",
                skills);
    }

    private static Map<String, Object> skill(String name, List<String> tools) {
        return Map.of(
                "name", name, "path", "plugins/hello/skills/" + name + "/SKILL.md", "plugin", "hello", "tools", tools);
    }

    @Test
    void compile_rejects_syntax_errors() {
        assertThatThrownBy(() -> CelPolicy.compile("skills.exists(s, ")).isInstanceOf(PolicyExpressionException.class);
    }

    @Test
    void compile_rejects_non_boolean_expressions() {
        assertThatThrownBy(() -> CelPolicy.compile("1 + 1")).isInstanceOf(PolicyExpressionException.class);
    }

    @Test
    void compile_rejects_undeclared_variables() {
        assertThatThrownBy(() -> CelPolicy.compile("nosuchvar == 'x'")).isInstanceOf(PolicyExpressionException.class);
    }

    @Test
    void matches_shell_tool_rule_against_declared_tools() {
        CelPolicy.Compiled rule = CelPolicy.compile("skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))");
        Map<String, Object> shelly = facts(List.of(), List.of(skill("shelly", List.of("Bash(git:*)", "Read"))));
        Map<String, Object> docsy = facts(List.of(), List.of(skill("docsy", List.of("Read"))));
        assertThat(CelPolicy.matches(rule, shelly)).isTrue();
        assertThat(CelPolicy.matches(rule, docsy)).isFalse();
    }

    @Test
    void matches_over_snapshot_and_file_facts() {
        CelPolicy.Compiled rule =
                CelPolicy.compile("snapshot.marketplace == \"corp\" && files.exists(f, f.path.endsWith(\".exe\"))");
        Map<String, Object> withExe = facts(List.of(Map.of("path", "bin/x.exe", "size", 10L)), List.of());
        Map<String, Object> without = facts(List.of(Map.of("path", "README.md", "size", 10L)), List.of());
        assertThat(CelPolicy.matches(rule, withExe)).isTrue();
        assertThat(CelPolicy.matches(rule, without)).isFalse();
    }

    @Test
    void evaluation_errors_raise_rather_than_answer() {
        CelPolicy.Compiled rule = CelPolicy.compile("files[1000].path == \"x\"");
        assertThatThrownBy(() -> CelPolicy.matches(rule, facts(List.of(), List.of())))
                .isInstanceOf(PolicyEvaluationException.class);
    }

    @Test
    void comprehension_bombs_hit_the_iteration_bound() {
        // 100 files and three nested alls: a million iterations, well over the bound. The
        // point is that a hostile expression errors out bounded instead of hanging the gate.
        List<Map<String, Object>> files = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            files.add(Map.of("path", "f" + i, "size", (long) i));
        }
        CelPolicy.Compiled bomb =
                CelPolicy.compile("files.all(a, files.all(b, files.all(c, a.size + b.size + c.size >= 0)))");
        assertThatThrownBy(() -> CelPolicy.matches(bomb, facts(files, List.of())))
                .isInstanceOf(PolicyEvaluationException.class);
    }
}
