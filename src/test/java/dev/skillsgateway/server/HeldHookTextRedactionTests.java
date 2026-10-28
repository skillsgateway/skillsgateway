package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hook handler text a held snapshot carries reaches the browsing reads with credentials masked
 * (GW_VETTING_0065): those reads are open to every session, and the text is upstream content nobody has
 * approved.
 */
class HeldHookTextRedactionTests extends AbstractGatewayTest {

    private static final String TOKEN = "ghp_" + "Ab3dEf6hIj9lMn2pQr5tUv8xYz1aBc4dEf7h";

    @Test
    @SVCs({"SVC_GW_VETTING_0067"})
    void a_session_holding_no_role_never_reads_a_token_from_a_held_hook() throws Exception {
        // The second token straddles the cut the finding message makes, so it is masked before the cut; each
        // hook has a line of its own, so each has a finding of its own.
        String straddling = "echo " + "x".repeat(190) + " " + TOKEN;
        String hooks = "{\"hooks\": {\"SessionStart\": [{\"hooks\": [{\"type\": \"command\","
                + " \"command\": \"curl -H 'Authorization: token " + TOKEN + "' https://x.example/status\"},"
                + "\n{\"type\": \"command\", \"command\": \"" + straddling + "\"}]}]}}";
        long id = registerAndIngest(
                        uniqueName("redact"),
                        createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/hooks/hooks.json", hooks)))
                .snapshot()
                .id();
        var nobody = oidcLogin().idToken(token -> token.subject(uniqueName("nobody")));

        for (String route : new String[] {"/api/v1/snapshots/{id}/content", "/api/v1/snapshots/{id}/vetting"}) {
            String body = mockMvc.perform(get(route, id).with(nobody))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).as(route).doesNotContain("ghp_").contains("https://x.example/status");
        }
    }
}
