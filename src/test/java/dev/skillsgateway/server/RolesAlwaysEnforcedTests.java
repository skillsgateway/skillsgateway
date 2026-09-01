package dev.skillsgateway.server;

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
 * Authorization with nothing switching it on (GW_0138).
 *
 * <p>This suite runs in the shared default context, which is the point: it sets no enforcement
 * property, because there is none to set. It replaces the compatibility suite that asserted the
 * opposite — that a session holding no role could do everything — a state the gateway no longer
 * has. That state was reached by installing the gateway and changing nothing, so the test that
 * described it was, read plainly, a test that the default deployment had no authorization.
 */
class RolesAlwaysEnforcedTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0138"})
    void a_session_holding_no_role_is_refused_with_no_enforcement_property_anywhere() throws Exception {
        var nobody = oidcLogin().idToken(token -> token.subject("nobody-" + uniqueName("p")));
        String name = uniqueName("enforced");

        // An admin-only mutation, an approver-or-admin mutation, and auditor-or-admin reads: the
        // same three classifications the old compatibility suite drove, now all refused.
        mockMvc.perform(post("/api/marketplaces")
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"url\": \"%s\"}"
                                .formatted(
                                        name,
                                        createUpstream(DEFAULT_MANIFEST)
                                                .toAbsolutePath()
                                                .toUri())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/marketplaces/{name}/sync", name)
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"on-demand\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit").with(nobody)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/retention/candidates").with(nobody)).andExpect(status().isForbidden());

        // The grants API is not writable staging data any more: it is the thing that grants roles.
        mockMvc.perform(post("/api/roles")
                        .with(nobody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principal\": \"future-admin\", \"role\": \"admin\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/roles").with(nobody)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/roles/{id}", 1).with(nobody)).andExpect(status().isForbidden());
    }

    @Test
    @SVCs({"SVC_GW_0138"})
    void the_session_endpoint_no_longer_reports_an_enforcement_flag() throws Exception {
        mockMvc.perform(get("/api/me").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolesEnabled").doesNotExist());
    }
}
