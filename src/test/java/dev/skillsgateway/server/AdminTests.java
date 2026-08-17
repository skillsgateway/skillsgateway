package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0010"})
    void listReturnsAllMarketplacesWithSnapshotStates() throws Exception {
        String heldName = uniqueName("corp");
        String rejectedName = uniqueName("corp");
        registerAndIngest(heldName, createUpstream(DEFAULT_MANIFEST));
        Registered toReject = registerAndIngest(rejectedName, createUpstream(DEFAULT_MANIFEST));
        approvalService.reject(toReject.snapshot().id(), "alice");

        String body = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> heldStates = JsonPath.read(body, "$[?(@.name == '%s')].snapshots[*].state".formatted(heldName));
        assertThat(heldStates).containsExactly("held");
        List<String> rejectedStates =
                JsonPath.read(body, "$[?(@.name == '%s')].snapshots[*].state".formatted(rejectedName));
        assertThat(rejectedStates).containsExactly("rejected");
    }
}
