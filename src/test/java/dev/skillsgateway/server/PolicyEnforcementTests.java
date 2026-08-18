package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The fail-closed policy gate at approval (GW_0090) and its ledger provenance (GW_0091). Every
 * rule here scopes itself to its own fixture marketplace and is deleted afterwards, so a deny
 * rule can never leak into another test's approval.
 */
class PolicyEnforcementTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SHELL_SKILL = """
            ---
            name: shelly
            description: a skill that declares shell tools
            allowed-tools: Bash(git:*), Read
            ---
            # Shelly
            """;

    @Autowired
    private GitStorage storage;

    private void createRule(String name, String expression, boolean enabled) throws Exception {
        mockMvc.perform(post("/api/policy/rules")
                        .with(oidcLogin().idToken(token -> token.subject("root")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "name", name,
                                "description", "enforcement fixture",
                                "expression", expression,
                                "enabled", enabled))))
                .andExpect(status().isOk());
    }

    private void deleteRule(String name) throws Exception {
        mockMvc.perform(delete("/api/policy/rules/{name}", name)
                        .with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk());
    }

    private static String scoped(String marketplace, String expression) {
        return "snapshot.marketplace == \"%s\" && (%s)".formatted(marketplace, expression);
    }

    @Test
    @SVCs({"SVC_GW_0090"})
    void matching_rule_denies_and_publishes_nothing() throws Exception {
        String name = uniqueName("policym");
        String rule = uniqueName("no-shell");
        Registered registered = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/shelly/SKILL.md", SHELL_SKILL)));
        createRule(rule, scoped(name, "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"), true);
        try {
            mockMvc.perform(post(
                                    "/api/snapshots/{id}/approve",
                                    registered.snapshot().id())
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.denials[0].rule").value(rule))
                    .andExpect(jsonPath("$.denials[0].outcome").value("matched"));
            assertThat(snapshotRepository
                            .findById(registered.snapshot().id())
                            .orElseThrow()
                            .state())
                    .isEqualTo("held");
            assertThat(storage.publishedIfServing(name)).isEmpty();
            assertThat(fetchLogRepository.list())
                    .noneMatch(entry -> "snapshot-approved".equals(entry.get("event"))
                            && registered.snapshot().sha().equals(entry.get("sha")));
        } finally {
            deleteRule(rule);
        }
    }

    @Test
    @SVCs({"SVC_GW_0090"})
    void disabled_and_non_matching_rules_do_not_deny() throws Exception {
        String name = uniqueName("policyn");
        String disabled = uniqueName("disabled-match");
        String nonMatching = uniqueName("non-matching");
        Registered registered = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/shelly/SKILL.md", SHELL_SKILL)));
        createRule(disabled, scoped(name, "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"), false);
        createRule(nonMatching, scoped(name, "files.exists(f, f.path.endsWith(\".exe\"))"), true);
        try {
            mockMvc.perform(post(
                                    "/api/snapshots/{id}/approve",
                                    registered.snapshot().id())
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isOk());
            assertThat(storage.publishedIfServing(name)).isPresent();
        } finally {
            deleteRule(disabled);
            deleteRule(nonMatching);
        }
    }

    @Test
    @SVCs({"SVC_GW_0090"})
    void a_rule_that_errors_at_evaluation_denies() throws Exception {
        String name = uniqueName("policye");
        String rule = uniqueName("erroring");
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST));
        createRule(rule, scoped(name, "files[1000].path == \"x\""), true);
        try {
            mockMvc.perform(post(
                                    "/api/snapshots/{id}/approve",
                                    registered.snapshot().id())
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.denials[0].rule").value(rule))
                    .andExpect(jsonPath("$.denials[0].outcome").value(org.hamcrest.Matchers.startsWith("error")));
            assertThat(storage.publishedIfServing(name)).isEmpty();
        } finally {
            deleteRule(rule);
        }
    }

    @Test
    @SVCs({"SVC_GW_0090"})
    void a_comprehension_bomb_denies_within_the_bound_instead_of_hanging() throws Exception {
        String name = uniqueName("policyb");
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            many.put("docs/file-" + i + ".md", "content " + i);
        }
        Registered registered = registerAndIngest(name, createUpstream(DEFAULT_MANIFEST, many));
        String rule = uniqueName("bomb");
        createRule(
                rule,
                scoped(name, "files.all(a, files.all(b, files.all(c, files.all(d, a.size + b.size >= 0))))"),
                true);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(30), () -> mockMvc.perform(post(
                                            "/api/snapshots/{id}/approve",
                                            registered.snapshot().id())
                                    .with(oidcLogin().idToken(token -> token.subject("alice"))))
                            .andExpect(status().isConflict())
                            .andExpect(
                                    jsonPath("$.denials[0].outcome").value(org.hamcrest.Matchers.startsWith("error"))));
            assertThat(storage.publishedIfServing(name)).isEmpty();
        } finally {
            deleteRule(rule);
        }
    }

    @Test
    @SVCs({"SVC_GW_0090"})
    void malformed_skill_frontmatter_denies_under_a_tools_rule() throws Exception {
        String name = uniqueName("policyf");
        String rule = uniqueName("no-shell-fm");
        String malformed = "---\nallowed-tools: [unclosed\n---\n# Broken\n";
        Registered registered = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/broken/SKILL.md", malformed)));
        createRule(rule, scoped(name, "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"), true);
        try {
            mockMvc.perform(post(
                                    "/api/snapshots/{id}/approve",
                                    registered.snapshot().id())
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.denials[0].outcome").value(org.hamcrest.Matchers.startsWith("error")));
            assertThat(storage.publishedIfServing(name)).isEmpty();
        } finally {
            deleteRule(rule);
        }
    }

    @Test
    @SVCs({"SVC_GW_0091"})
    void every_deciding_rule_lands_on_the_ledger_with_its_outcome() throws Exception {
        String name = uniqueName("policyl");
        String matching = uniqueName("aa-matching");
        String erroring = uniqueName("bb-erroring");
        Registered registered = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/shelly/SKILL.md", SHELL_SKILL)));
        createRule(matching, scoped(name, "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))"), true);
        createRule(erroring, scoped(name, "files[1000].path == \"x\""), true);
        try {
            mockMvc.perform(post(
                                    "/api/snapshots/{id}/approve",
                                    registered.snapshot().id())
                            .with(oidcLogin().idToken(token -> token.subject("alice"))))
                    .andExpect(status().isConflict());
            var denials = fetchLogRepository.list().stream()
                    .filter(entry -> "policy-denied".equals(entry.get("event")))
                    .filter(entry -> registered.snapshot().sha().equals(entry.get("sha")))
                    .toList();
            assertThat(denials).hasSize(2);
            assertThat(denials).allSatisfy(entry -> {
                assertThat(entry.get("principal")).isEqualTo("alice");
                assertThat(entry.get("marketplace")).isEqualTo(name);
            });
            assertThat(denials.stream().map(entry -> entry.get("detail").toString()))
                    .anySatisfy(detail ->
                            assertThat(detail).contains("rule=" + matching).contains("outcome=matched"))
                    .anySatisfy(detail ->
                            assertThat(detail).contains("rule=" + erroring).contains("outcome=error"));
            assertThat(fetchLogRepository.list())
                    .noneMatch(entry -> "snapshot-approved".equals(entry.get("event"))
                            && registered.snapshot().sha().equals(entry.get("sha")));
        } finally {
            deleteRule(matching);
            deleteRule(erroring);
        }
    }
}
