package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The compatibility half of claim mapping (GW_0098): mappings configured while enforcement is off.
 * They are reported so an operator can dry-run a mapping before flipping the switch, and they
 * change nothing about who may do what — because with enforcement off, nobody is refused anything.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.roles.enabled=false",
            "skills-gateway.roles.claim=groups",
            "skills-gateway.roles.mappings[0].claim-value=gw-auditors",
            "skills-gateway.roles.mappings[0].role=auditor"
        })
class ClaimRolesDisabledTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0098"})
    void mappings_are_reported_but_enforce_nothing_while_the_switch_is_off() throws Exception {
        var carol = oidcLogin()
                .idToken(token -> token.subject("dryrun-carol").claim("groups", java.util.List.of("gw-auditors")));

        // Reported, so the mapping can be verified before enforcement is turned on...
        mockMvc.perform(get("/api/me").with(carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolesEnabled").value(false))
                .andExpect(jsonPath("$.roles[0].role").value("auditor"))
                .andExpect(jsonPath("$.roles[0].source").value("claim"));

        // ... and inert: an auditor mapping would refuse this mutation once enforcement is on.
        mockMvc.perform(post("/api/marketplaces")
                        .with(carol)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://example.com/x.git\"}"
                                .formatted(uniqueName("dryrun"))))
                .andExpect(status().isCreated());
    }
}
