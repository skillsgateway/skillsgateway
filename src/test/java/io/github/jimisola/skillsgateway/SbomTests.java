package io.github.jimisola.skillsgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SbomTests extends AbstractGatewayTest {

    @Test
    @SVCs({"SVC_GW_0014"})
    void sbomEndpointServesCycloneDxBom() throws Exception {
        String listing = mockMvc.perform(get("/actuator/sbom").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> ids = JsonPath.read(listing, "$.ids");
        assertThat(ids).contains("application");

        String bom = mockMvc.perform(get("/actuator/sbom/application").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(bom).contains("CycloneDX");
        List<Object> components = JsonPath.read(bom, "$.components");
        assertThat(components).isNotEmpty();
    }
}
