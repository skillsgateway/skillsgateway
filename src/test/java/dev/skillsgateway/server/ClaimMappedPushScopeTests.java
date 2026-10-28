package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A push scope is granted on the standing the role model honours, claims included (GW_FACADE_0007):
 * here the administrator holds no configuration entry and no grant row, only the {@code gw-admins}
 * claim.
 */
class ClaimMappedPushScopeTests extends AbstractClaimMappingTest {

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Test
    @SVCs({"SVC_GW_FACADE_0035"})
    void a_claim_derived_administrator_is_given_a_push_scope() throws Exception {
        String hosted = uniqueName("claimpush");
        marketplaceRepository.register(
                hosted, null, null, Marketplace.ORIGIN_HOSTED, Marketplace.PUSH_APPEND_ONLY, null);
        var claimAdmin = oidcLogin()
                .idToken(token -> token.subject(uniqueName("claimadmin")).claim("groups", List.of("gw-admins")));

        mockMvc.perform(post("/api/v1/tokens")
                        .with(claimAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"publisher\",\"pushScopes\":[\"%s\"]}".formatted(hosted)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushScopes[0]").value(hosted));
    }
}
