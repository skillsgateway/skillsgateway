package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/** Pure URL → forge API mapping across the supported providers (GW_0021). */
class ForgeTargetTests {

    @Test
    @SVCs({"SVC_GW_0021"})
    void cloneUrlsOfLargeProvidersMapToTheirMetadataApis() throws Exception {
        assertThat(ForgeMetadataService.target("https://github.com/acme/skills.git"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("github");
                    assertThat(t.apiUrl()).isEqualTo("https://api.github.com/repos/acme/skills");
                });
        assertThat(ForgeMetadataService.target("https://gitlab.example.com/group/sub/skills.git"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("gitlab");
                    assertThat(t.apiUrl()).isEqualTo("https://gitlab.example.com/api/v4/projects/group%2Fsub%2Fskills");
                });
        assertThat(ForgeMetadataService.target("https://bitbucket.org/acme/skills.git"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("bitbucket");
                    assertThat(t.apiUrl()).isEqualTo("https://api.bitbucket.org/2.0/repositories/acme/skills");
                });
        assertThat(ForgeMetadataService.target("https://bitbucket.corp.example/scm/acme/skills.git"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("bitbucket-server");
                    assertThat(t.apiUrl())
                            .isEqualTo("https://bitbucket.corp.example/rest/api/1.0/projects/acme/repos/skills");
                });
        assertThat(ForgeMetadataService.target("https://dev.azure.com/acme/platform/_git/skills"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("azure-devops");
                    assertThat(t.apiUrl())
                            .isEqualTo(
                                    "https://dev.azure.com/acme/platform/_apis/git/repositories/skills?api-version=7.1");
                });
        assertThat(ForgeMetadataService.target("https://codeberg.org/acme/skills.git"))
                .hasValueSatisfying(t -> {
                    assertThat(t.forge()).isEqualTo("gitea");
                    assertThat(t.apiUrl()).isEqualTo("https://codeberg.org/api/v1/repos/acme/skills");
                });
        // Not repo-shaped or not http(s): no lookup at all.
        assertThat(ForgeMetadataService.target("https://example.com/just-one-segment"))
                .isEmpty();
        assertThat(ForgeMetadataService.target("file:///tmp/local-repo")).isEmpty();
    }
}
