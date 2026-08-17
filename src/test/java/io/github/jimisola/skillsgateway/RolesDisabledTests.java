package io.github.jimisola.skillsgateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The compatibility half of GW_0068, in the shared default context: with
 * {@code skills-gateway.roles.enabled=false} — the default — a session with no role can do
 * everything, exactly as before roles existed, and the grants API is inert staging data.
 */
class RolesDisabledTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0068"})
    void with_roles_disabled_a_no_role_session_can_do_everything_and_stage_grants() throws Exception {
        var nobody = oidcLogin().idToken(token -> token.subject("nobody-" + uniqueName("p")));
        String name = uniqueName("rolesoff");

        // Privileged operations across the classification: an admin-only mutation, an
        // approver-or-admin mutation, and auditor-or-admin reads — all pass with no grant.
        mockMvc.perform(post("/api/marketplaces")
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"url\": \"%s\"}"
                                .formatted(
                                        name,
                                        createUpstream(DEFAULT_MANIFEST)
                                                .toAbsolutePath()
                                                .toUri())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/marketplaces/{name}/ingest", name).with(nobody))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/marketplaces/{name}/sync", name)
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"on-demand\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit").with(nobody)).andExpect(status().isOk());
        mockMvc.perform(get("/api/retention/candidates").with(nobody)).andExpect(status().isOk());

        // /api/me says so, and the grants API is writable staging data until the switch flips.
        mockMvc.perform(get("/api/me").with(nobody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolesEnabled").value(false));
        String staged = mockMvc.perform(post("/api/roles")
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal\": \"future-admin\", \"role\": \"admin\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long grantId = Long.parseLong(staged.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1"));
        mockMvc.perform(get("/api/roles").with(nobody)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/roles/{id}", grantId).with(nobody)).andExpect(status().isNoContent());
    }
}
