package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.DevInsecureAuthGuard;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Development-only escape hatch: with dev-insecure-auth the web surface needs no OIDC session and
 * requests act as the synthetic user "dev". Off by default — GW_0011 governs default deployments.
 *
 * <p>This context also boots the escape hatch's startup guard (GW_0110) against the shipped
 * identity-provider placeholders, which is the local development loop the guard must never refuse:
 * the guard is autowired below, so it being absent — or having refused — fails this test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"skills-gateway.dev-insecure-auth=true", "skills-gateway.data-dir=target/test-git-data-dev"})
class DevAuthTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private DevInsecureAuthGuard devInsecureAuthGuard;

    @Test
    void devModeServesTheApiWithoutASessionAsUserDev() throws Exception {
        assertThat(devInsecureAuthGuard.signals())
                .as("the local development loop configures no identity provider")
                .isEmpty();

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dev"));
        mockMvc.perform(get("/api/marketplaces")).andExpect(status().isOk());
    }

    /**
     * The escape hatch opens the web chain, never the machine chain (GW_0127). It follows the git
     * facade's posture, which stays strict in this mode for the same reason: a mode in which every
     * bearer value authenticated would be a very quiet way to lose the control plane in a copied
     * configuration — and unlike a browser session, a machine credential leaves no clue that it
     * was ever needed.
     */
    @Test
    @SVCs({"SVC_GW_0127"})
    void dev_insecure_auth_does_not_open_the_bearer_path() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        for (String value : List.of("sgw_definitely-not-a-token", "", "   ")) {
            mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + value))
                    .andExpect(status().isUnauthorized());
        }
        // The scheme with nothing after it at all — not even the trailing space. This mode is the
        // only place the distinction is observable: everywhere else both chains answer 401, so a
        // matcher that stopped recognising the bare scheme would look identical. Here the web
        // chain permits everything, so falling through to it would answer 200. A surviving mutant
        // on the matcher is what found this; the assertion above could not.
        mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "Bearer"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/marketplaces").header(HttpHeaders.AUTHORIZATION, "bearer"))
                .andExpect(status().isUnauthorized());
        // Not merely 401 for everything: the same request without the header is still open, which
        // is what the mode is for.
        mockMvc.perform(get("/api/marketplaces")).andExpect(status().isOk());
    }
}
