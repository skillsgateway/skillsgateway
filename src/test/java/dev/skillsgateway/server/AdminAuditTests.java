package dev.skillsgateway.server;

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
    @SVCs({"SVC_GW_AUDIT_0002"})
    void adminActionsAreRecordedInTheLedgerWithTheActingIdentity() throws Exception {
        String me = JsonPath.read(
                mockMvc.perform(get("/api/v1/me").with(oidcLogin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.username");

        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}"
                                .formatted(name, upstream.toUri().toString())))
                .andExpect(status().isCreated());
        String snapshot = mockMvc.perform(
                        post("/api/v1/marketplaces/%s/ingest".formatted(name)).with(oidcLogin()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int snapshotId = JsonPath.read(snapshot, "$.id");
        mockMvc.perform(post("/api/v1/snapshots/%d/approve".formatted(snapshotId))
                        .with(oidcLogin()))
                .andExpect(status().isOk());

        String issued = mockMvc.perform(post("/api/v1/tokens")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"audit-test\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int tokenId = JsonPath.read(issued, "$.id");
        mockMvc.perform(delete("/api/v1/tokens/%d".formatted(tokenId)).with(oidcLogin()))
                .andExpect(status().isNoContent());

        // The browse read is a page now (GW_AUDIT_0008), so ask for one big enough to hold this
        // test's own entries rather than relying on the default.
        String audit = mockMvc.perform(
                        get("/api/v1/audit").param("limit", "1000").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String event : List.of("marketplace-registered", "snapshot-ingested", "snapshot-approved")) {
            List<String> principals = JsonPath.read(
                    audit, "$.entries[?(@.event == '%s' && @.marketplace == '%s')].principal".formatted(event, name));
            assertThat(principals).as(event).containsExactly(me);
        }
        for (String event : List.of("token-created", "token-revoked")) {
            List<String> principals = JsonPath.read(audit, "$.entries[?(@.event == '%s')].principal".formatted(event));
            assertThat(principals).as(event).contains(me);
        }
    }
}
