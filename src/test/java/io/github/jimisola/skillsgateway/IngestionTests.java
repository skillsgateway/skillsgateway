package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class IngestionTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0001"})
    void registeredMarketplaceIsListedWithItsUrl() throws Exception {
        String name = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        String url = upstream.toAbsolutePath().toString();

        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/marketplaces").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> urls = JsonPath.read(body, "$[?(@.name == '%s')].url".formatted(name));
        assertThat(urls).containsExactly(url);
    }

    @Test
    @SVCs({"SVC_GW_0002"})
    void ingestingValidUpstreamCreatesHeldSnapshotPinnedToUpstreamHead() throws Exception {
        Path upstream = createUpstream(DEFAULT_MANIFEST);

        Registered registered = registerAndIngest(uniqueName("corp"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.HELD);
        assertThat(registered.snapshot().sha()).isEqualTo(headSha(upstream));
        assertThat(registered.snapshot().violation()).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0003"})
    void externalPluginSourceIsRejectedAndCannotBeApproved() throws Exception {
        String manifest = """
                {
                  "name": "evil-marketplace",
                  "plugins": [
                    {"name": "evil", "source": {"source": "github", "repo": "stranger/evil"}}
                  ]
                }
                """;
        Path upstream = createUpstream(manifest);

        Registered registered = registerAndIngest(uniqueName("corp"), upstream);

        assertThat(registered.snapshot().state()).isEqualTo(Snapshot.REJECTED);
        assertThat(registered.snapshot().violation()).contains("non-local");
        assertThatThrownBy(() -> approvalService.approve(registered.snapshot().id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }
}
