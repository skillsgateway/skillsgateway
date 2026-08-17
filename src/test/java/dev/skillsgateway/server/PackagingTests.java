package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Validates that the packaging artifacts (Dockerfile, Helm chart) exist and stay consistent with
 * the application: the image runs the native binary and the chart wires the image, health probes,
 * and the PostgreSQL and OIDC configuration the application expects. The container itself is built
 * and smoke-tested in CI (native workflow).
 */
class PackagingTests {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    @SVCs({"SVC_GW_0015"})
    void packagingArtifactsAreConsistent() throws IOException {
        String dockerfile = Files.readString(REPO_ROOT.resolve("Dockerfile"));
        assertThat(dockerfile).contains("COPY target/skills-gateway-server ");
        assertThat(dockerfile).contains("ENTRYPOINT [\"/app/skills-gateway-server\"]");
        assertThat(dockerfile).contains("EXPOSE 8080");

        Path chart = REPO_ROOT.resolve("helm/skills-gateway");
        assertThat(Files.readString(chart.resolve("Chart.yaml"))).contains("name: skills-gateway");

        String deployment = Files.readString(chart.resolve("templates/deployment.yaml"));
        assertThat(deployment).contains("SPRING_DATASOURCE_URL");
        assertThat(deployment).contains("SPRING_DATASOURCE_USERNAME");
        assertThat(deployment).contains("SPRING_DATASOURCE_PASSWORD");
        assertThat(deployment).contains("SGW_OIDC_CLIENT_ID");
        assertThat(deployment).contains("SGW_OIDC_CLIENT_SECRET");
        assertThat(deployment).contains("path: /actuator/health");
        assertThat(deployment).contains(".Values.image.repository");

        assertThat(Files.exists(chart.resolve("templates/service.yaml"))).isTrue();
        assertThat(Files.readString(REPO_ROOT.resolve("compose.yaml"))).contains("SPRING_DATASOURCE_URL");
    }
}
