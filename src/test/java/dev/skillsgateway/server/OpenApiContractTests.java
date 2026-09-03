package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.api.OpenAPI;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;

/**
 * The contract the API keeps with everything built from it: the served document says which release
 * it describes, the copy published in the repository is the same document, and the workflow that
 * refuses an undeclared breaking change carries the rules that make it mean something.
 */
class OpenApiContractTests extends AbstractGatewayTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path PUBLISHED = REPO_ROOT.resolve("src/main/frontend/openapi.json");
    private static final String OASDIFF_SEVERITY_LEVELS = ".oasdiff-severity-levels.txt";

    private static final String REGENERATE =
            "cp target/openapi.json src/main/frontend/openapi.json && (cd src/main/frontend && "
                    + "pnpm run gen:api-types)";

    @Autowired
    private BuildProperties buildProperties;

    private String servedDocument() throws Exception {
        return mockMvc.perform(get("/v3/api-docs").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_0105"})
    void servedDocumentDeclaresTheBuildsVersion() throws Exception {
        String served = JsonPath.read(servedDocument(), "$.info.version");

        // BuildProperties is only present when the build ran build-info; if it silently stopped
        // running, the fallback would keep the document parseable and hide that from us.
        assertThat(buildProperties.getVersion())
                .as("build-info.properties present, so the document can report a real release")
                .isNotBlank()
                .isNotEqualTo(OpenAPI.UNKNOWN_VERSION);
        assertThat(served)
                .as("the served document declares the release it describes, not a hand-written label")
                .isEqualTo(buildProperties.getVersion())
                .isNotEqualTo("v1")
                .isNotEqualTo(OpenApiSnapshot.PLACEHOLDER_VERSION);
    }

    /** The check itself, so the negative test below can run the real one rather than an imitation. */
    private static void assertPublishedIsCurrent(String published, String servedDocument) throws IOException {
        assertThat(published)
                .as(
                        "%s is stale — the portal's types and mocks would describe an API the gateway no"
                                + " longer has, and the compatibility gate would diff a document nobody"
                                + " serves. Regenerate it:%n%n  %s%n",
                        PUBLISHED, REGENERATE)
                .isEqualTo(OpenApiSnapshot.publishedForm(servedDocument));
    }

    @Test
    @SVCs({"SVC_GW_0106"})
    void publishedDocumentMatchesTheServedOne() throws Exception {
        assertPublishedIsCurrent(Files.readString(PUBLISHED), servedDocument());
    }

    @Test
    void theStalenessCheckCanActuallyFail() throws Exception {
        // A check nobody has seen fail is a check nobody should trust: drop one endpoint from the
        // published copy and the real assertion above must go red.
        String served = servedDocument();
        String drifted = OpenApiSnapshot.publishedForm(served).replace("\"/api/tokens\"", "\"/api/tokenz\"");

        assertThatThrownBy(() -> assertPublishedIsCurrent(drifted, served))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is stale")
                .hasMessageContaining("gen:api-types");
    }

    @Test
    @SVCs({"SVC_GW_0107"})
    void contractWorkflowCarriesTheBreakingChangeContract() throws IOException {
        String workflow = Files.readString(REPO_ROOT.resolve(".github/workflows/api-contract.yml"));

        // a title fixed after a red check has to re-run, as in check-semantic-pr.yml
        assertThat(workflow).contains("types: [opened, edited, reopened, synchronize]");

        // the baseline is what this branch forked from, not whatever main has become since
        assertThat(workflow).contains("fetch-depth: 0");
        assertThat(workflow).contains("git merge-base");
        assertThat(workflow).contains("src/main/frontend/openapi.json");

        // the gate itself, and the two conditions that let a deliberate break through
        assertThat(workflow).contains("oasdiff");
        assertThat(workflow).contains("BREAKING CONTRACT");
        assertThat(workflow).contains("BREAKING CHANGE");

        // a refusal that only refuses teaches nobody: the escape is named in the failure
        assertThat(workflow).contains("/api/v1");
    }

    @Test
    @SVCs({"SVC_GW_0107"})
    void removingAResponseFieldIsAnErrorNotAWarning() throws IOException {
        // oasdiff rates removing a response field a warning unless the schema marks the field
        // required, and no response schema here does — so fail-on: ERR let it through (#216).
        assertThat(Files.readString(REPO_ROOT.resolve(".oasdiff.yaml")))
                .as("the severity file the oasdiff step picks up from the repository root")
                .contains("severity-levels: " + OASDIFF_SEVERITY_LEVELS);
        assertThat(Files.readString(REPO_ROOT.resolve(OASDIFF_SEVERITY_LEVELS)))
                .contains("response-optional-property-removed\terr");
    }
}
