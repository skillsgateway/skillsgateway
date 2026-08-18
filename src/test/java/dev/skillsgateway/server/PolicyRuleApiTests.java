package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Rule lifecycle through the API (GW_0089): compiled at write time, refused when it does not
 * compile to a boolean, unique by name, audited on the ledger.
 */
class PolicyRuleApiTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String ruleJson(String name, String expression, boolean enabled) throws Exception {
        return MAPPER.writeValueAsString(
                Map.of("name", name, "description", "test rule", "expression", expression, "enabled", enabled));
    }

    @Test
    @SVCs({"SVC_GW_0089"})
    void rule_lifecycle_is_compiled_gated_and_audited() throws Exception {
        String name = uniqueName("rule");
        try {
            // Creation compiles; the stored rule is listed back.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(name, "snapshot.marketplace == \"nowhere\"", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.enabled").value(true));
            mockMvc.perform(get("/api/policy/rules").with(oidcLogin().idToken(token -> token.subject("root"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == '%s')].expression".formatted(name))
                            .value("snapshot.marketplace == \"nowhere\""));

            // A syntactically broken expression is refused and never stored.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(uniqueName("broken"), "skills.exists(s,", true)))
                    .andExpect(status().isUnprocessableContent());

            // A non-boolean expression is refused: the gate needs a verdict, not a value.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(uniqueName("nonbool"), "1 + 1", true)))
                    .andExpect(status().isUnprocessableContent());

            // An expression over undeclared variables is refused.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(uniqueName("novar"), "nosuchvar == 2", true)))
                    .andExpect(status().isUnprocessableContent());

            // A duplicate name is a conflict.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(name, "snapshot.marketplace == \"elsewhere\"", true)))
                    .andExpect(status().isConflict());

            // Update converges expression and enabled; an update that does not compile changes nothing.
            mockMvc.perform(put("/api/policy/rules/{name}", name)
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(name, "snapshot.marketplace == \"elsewhere\"", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
            mockMvc.perform(put("/api/policy/rules/{name}", name)
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson(name, "definitely not CEL ((", false)))
                    .andExpect(status().isUnprocessableContent());
            mockMvc.perform(get("/api/policy/rules").with(oidcLogin().idToken(token -> token.subject("root"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == '%s')].expression".formatted(name))
                            .value("snapshot.marketplace == \"elsewhere\""));

            // Updating or deleting an unknown rule is not-found.
            mockMvc.perform(put("/api/policy/rules/{name}", "no-such-rule")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson("no-such-rule", "true", true)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/policy/rules/{name}", "no-such-rule")
                            .with(oidcLogin().idToken(token -> token.subject("root"))))
                    .andExpect(status().isNotFound());

            // A malformed name is refused before compilation.
            mockMvc.perform(post("/api/policy/rules")
                            .with(oidcLogin().idToken(token -> token.subject("root")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleJson("Not A Name!", "true", true)))
                    .andExpect(status().isUnprocessableContent());
        } finally {
            mockMvc.perform(delete("/api/policy/rules/{name}", name)
                            .with(oidcLogin().idToken(token -> token.subject("root"))))
                    .andExpect(status().isOk());
        }

        // The whole lifecycle is on the ledger, attributed to the acting identity.
        var events = fetchLogRepository.list().stream()
                .filter(entry -> entry.get("detail") != null
                        && entry.get("detail").toString().contains("rule=" + name))
                .toList();
        assertThat(events.stream().map(entry -> entry.get("event")))
                .contains("policy-rule-created", "policy-rule-updated", "policy-rule-deleted");
        assertThat(events)
                .allSatisfy(entry -> assertThat(entry.get("principal")).isEqualTo("root"));
    }
}
