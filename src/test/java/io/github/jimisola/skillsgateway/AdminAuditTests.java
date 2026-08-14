package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AdminAuditTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0022"})
    void adminActionsAreRecordedInTheLedgerWithTheActingIdentity() throws Exception {
        String me = JsonPath.read(
                mockMvc.perform(get("/api/me").with(oidcLogin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.username");

        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}"
                                .formatted(name, upstream.toUri().toString())))
                .andExpect(status().isCreated());
        String snapshot = mockMvc.perform(
                        post("/api/marketplaces/%s/ingest".formatted(name)).with(oidcLogin()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int snapshotId = JsonPath.read(snapshot, "$.id");
        mockMvc.perform(post("/api/snapshots/%d/approve".formatted(snapshotId)).with(oidcLogin()))
                .andExpect(status().isOk());

        String issued = mockMvc.perform(post("/api/tokens")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"audit-test\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int tokenId = JsonPath.read(issued, "$.id");
        mockMvc.perform(delete("/api/tokens/%d".formatted(tokenId)).with(oidcLogin()))
                .andExpect(status().isNoContent());

        String audit = mockMvc.perform(get("/api/audit").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String event : List.of("marketplace-registered", "snapshot-ingested", "snapshot-approved")) {
            List<String> principals = JsonPath.read(
                    audit, "$[?(@.event == '%s' && @.marketplace == '%s')].principal".formatted(event, name));
            assertThat(principals).as(event).containsExactly(me);
        }
        for (String event : List.of("token-created", "token-revoked")) {
            List<String> principals = JsonPath.read(audit, "$[?(@.event == '%s')].principal".formatted(event));
            assertThat(principals).as(event).contains(me);
        }
    }
}
