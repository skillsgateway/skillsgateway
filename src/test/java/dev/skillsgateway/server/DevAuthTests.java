package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.auth.DevInsecureAuthGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
}
