package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * A claim-derived role both reports and binds (GW_AUTH_0015, GW_AUTH_0025).
 *
 * <p>This suite used to assert the opposite half: that a configured mapping was reported but inert,
 * so an operator could dry-run it before turning enforcement on. There is no off state to dry-run
 * against any more, so the interesting assertion is the one that state made impossible — the mapped
 * role is what the caller may do, and nothing more than that.
 *
 * <p>It shares {@link AbstractClaimMappingTest}'s mappings, of which it uses one. The refusal below
 * is sharper there than it was under its own declaration: that context's administrators were the
 * shared fixture's {@code user,root,alice}, and this one's are a single name that is not the caller.
 */
class ClaimMappedRoleIsEnforcedTests extends AbstractClaimMappingTest {

    @Test
    @SVCs({"SVC_GW_AUTH_0015.1", "SVC_GW_AUTH_0015.3"})
    void a_mapped_auditor_reads_the_ledger_and_is_refused_an_admin_mutation() throws Exception {
        var carol = oidcLogin()
                .idToken(token -> token.subject("dryrun-carol").claim("groups", java.util.List.of("gw-auditors")));

        // Reported, with the source that produced it...
        mockMvc.perform(get("/api/me").with(carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role").value("auditor"))
                .andExpect(jsonPath("$.roles[0].source").value("claim"));

        // ... and binding: the auditor surface opens, and an admin mutation does not.
        mockMvc.perform(get("/api/audit").with(carol)).andExpect(status().isOk());
        mockMvc.perform(post("/api/marketplaces")
                        .with(carol)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://example.com/x.git\"}"
                                .formatted(uniqueName("mapped"))))
                .andExpect(status().isForbidden());
    }
}
