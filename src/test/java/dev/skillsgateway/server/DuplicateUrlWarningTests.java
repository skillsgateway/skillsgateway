package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Registering the same upstream twice under different names is a legitimate way to test a
 * marketplace before promoting it (GW_INGEST_0029), so the gateway warns rather than refuses. The warning
 * lives in the registration response itself, so it reaches every caller — not only the portal's
 * own client-side check.
 */
class DuplicateUrlWarningTests extends AbstractGatewayTest {

    private String register(String name, String url) throws Exception {
        return mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}".formatted(name, url)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0029"})
    void a_normalized_duplicate_url_warns_and_still_registers() throws Exception {
        String first = uniqueName("dup-first");
        String second = uniqueName("dup-second");
        register(first, "https://example.invalid/acme/%s.git".formatted(first));

        // Differs only by host case, a trailing slash and the absent '.git' suffix.
        String created = register(second, "https://EXAMPLE.invalid/acme/%s/".formatted(first));

        List<String> warnings = JsonPath.read(created, "$.warnings");
        assertThat(warnings).containsExactly("url already registered as " + first);
        // Warn, never block: the second marketplace exists.
        assertThat(marketplaceRepository.findByName(second)).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0029"})
    void a_url_matching_no_existing_marketplace_carries_no_warning() throws Exception {
        String name = uniqueName("nodup");

        String created = register(name, "https://example.invalid/acme/%s.git".formatted(name));

        assertThat(JsonPath.<List<String>>read(created, "$.warnings")).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0029"})
    void a_warning_names_every_marketplace_sharing_the_normalized_url() throws Exception {
        String first = uniqueName("dup-multi-a");
        String second = uniqueName("dup-multi-b");
        String third = uniqueName("dup-multi-c");
        String url = "https://example.invalid/acme/%s.git".formatted(first);
        register(first, url);
        register(second, url);

        String created = register(third, url);

        assertThat(JsonPath.<List<String>>read(created, "$.warnings"))
                .containsExactlyInAnyOrder("url already registered as " + first, "url already registered as " + second);
    }
}
