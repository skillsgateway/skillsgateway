package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The content inventory lists each skill once however many plugins declare its source (GW_INGEST_0074):
 * it used to re-expand the skill tree once per manifest entry, so its output grew as plugins times
 * skills while the stored content stayed tiny.
 */
class InventoryBoundTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_INGEST_0074"})
    void a_manifest_repeating_one_source_lists_its_skills_once_quickly() throws Exception {
        StringBuilder plugins = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            plugins.append(i == 0 ? "" : ",").append("{\"name\":\"p%d\",\"source\":\"./\"}".formatted(i));
        }
        Map<String, String> skills = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            skills.put("skills/s%d/SKILL.md".formatted(i), CONFORMANT_SKILL);
        }
        Path upstream = createUpstream("{\"name\":\"dup\",\"plugins\":[%s]}".formatted(plugins), skills);
        long id =
                registerAndIngest(uniqueName("dupsource"), upstream).snapshot().id();

        String body = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> mockMvc.perform(get("/api/v1/snapshots/{id}/content", id)
                                .with(oidcLogin().idToken(token -> token.subject("alice"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());

        List<Object> listed = JsonPath.read(body, "$.plugins[*].skills[*]");
        assertThat(listed).as("skills listed across all plugins").hasSizeLessThanOrEqualTo(500);
    }
}
