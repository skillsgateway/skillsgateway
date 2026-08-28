package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the springdoc OpenAPI document and snapshots it to target/openapi.json — the input for
 * the portal's type generation (ADR 0003: MSW/types are contract-derived, never hand-authored).
 *
 * <p>The snapshot is written in {@link OpenApiSnapshot#publishedForm published form}, which is what
 * {@code src/main/frontend/openapi.json} must equal (GW_0106).
 */
class OpenApiDocsTests extends AbstractGatewayTest {

    @Test
    void openApiDocumentDescribesTheAdminApi() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> paths = JsonPath.read(doc, "$.paths");
        assertThat(paths).containsKeys("/api/marketplaces", "/api/tokens", "/api/audit");

        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, OpenApiSnapshot.publishedForm(doc));
    }
}
