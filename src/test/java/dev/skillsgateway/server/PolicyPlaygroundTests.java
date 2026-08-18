package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.storage.GitStorage;
import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The rule playground (GW_0092): evaluates any expression against a real snapshot and is provably
 * inert — no ledger append, no rule stored, no state change, on success and on every error path.
 */
class PolicyPlaygroundTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SHELL_SKILL = """
            ---
            name: shelly
            description: a skill that declares shell tools
            allowed-tools: Bash(git:*), Read
            ---
            # Shelly
            """;

    private String body(long snapshotId, String expression) throws Exception {
        return MAPPER.writeValueAsString(Map.of("snapshotId", snapshotId, "expression", expression));
    }

    @Autowired
    private GitStorage storage;

    @Test
    @SVCs({"SVC_GW_0092"})
    void the_playground_answers_and_changes_nothing() throws Exception {
        String name = uniqueName("playground");
        Registered registered = registerAndIngest(
                name, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/skills/shelly/SKILL.md", SHELL_SKILL)));
        long snapshotId = registered.snapshot().id();
        long ledgerBefore = fetchLogRepository.list().size();

        // Matched: the shell-tool rule from the issue, against real snapshot content.
        mockMvc.perform(post("/api/policy/playground")
                        .with(oidcLogin().idToken(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(snapshotId, "skills.exists(s, s.tools.exists(t, t.startsWith(\"Bash\")))")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        // Not matched.
        mockMvc.perform(post("/api/policy/playground")
                        .with(oidcLogin().idToken(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(snapshotId, "files.exists(f, f.path.endsWith(\".exe\"))")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));

        // A compile error is an answer, not a 500 — and echoes no snapshot content.
        mockMvc.perform(post("/api/policy/playground")
                        .with(oidcLogin().idToken(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(snapshotId, "skills.exists(s,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").doesNotExist())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));

        // An evaluation error is an answer too.
        mockMvc.perform(post("/api/policy/playground")
                        .with(oidcLogin().idToken(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(snapshotId, "files[1000].path == \"x\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").doesNotExist())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));

        // An unknown snapshot is not-found.
        mockMvc.perform(post("/api/policy/playground")
                        .with(oidcLogin().idToken(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(999_999_999L, "true")))
                .andExpect(status().isNotFound());

        // Provably inert: nothing appended, nothing stored, nothing decided, nothing served.
        assertThat(fetchLogRepository.list()).hasSize((int) ledgerBefore);
        mockMvc.perform(get("/api/policy/rules").with(oidcLogin().idToken(token -> token.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.description == 'enforcement fixture')]").isEmpty());
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo("held");
        assertThat(storage.publishedIfServing(name)).isEmpty();
    }
}
