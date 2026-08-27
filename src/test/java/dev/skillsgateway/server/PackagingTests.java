package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

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

    @Test
    @SVCs({"SVC_GW_0072"})
    void releaseWorkflowCarriesThePublishByDigestContract() throws IOException {
        Path file = REPO_ROOT.resolve(".github/workflows/native.yml");
        Map<String, Object> wf = parse(file);
        String body = Files.readString(file);

        // Publishes to the project namespace, by commit SHA and latest on main...
        assertThat(body).contains("ghcr.io/skillsgateway/skillsgateway");
        assertThat(body).contains("sha-${SHA}");

        // ...and by the released version when release.yml drives it. The version is
        // an input, never parsed out of a ref.
        assertThat(triggers(wf)).containsKey("workflow_call");
        assertThat(inputNames(wf, "workflow_call")).contains("version");
        assertThat(body).as("the version is never derived from a ref name").doesNotContain("github.ref_name");

        // A hand-pushed tag must not publish: no tag filter survives on the push
        // trigger, so the only way in is a main push or the release workflow.
        Map<String, Object> push = section(triggers(wf), "push");
        assertThat(push).as("push trigger present").isNotNull();
        assertThat(push).as("a hand-pushed tag must not publish").doesNotContainKey("tags");
        assertThat(push.get("branches")).isEqualTo(List.of("main"));

        // Every publish step is reachable only from a main push or from the release
        // workflow. A bare `github.event_name == 'push'` test would be false under
        // workflow_call -- the caller's event is workflow_dispatch -- so the release
        // path needs the second clause, and a schedule or bare dispatch matches
        // neither clause.
        for (String step : List.of("Log in to GHCR", "Push image", "Attest SBOM")) {
            assertThat(stepCondition(wf, "native", step))
                    .as("%s is gated to a main push or the release workflow", step)
                    .isEqualTo("github.event_name == 'push' || inputs.version != ''");
        }

        // Permissions publishing and attestation require.
        Map<String, Object> perms = section(job(wf, "native"), "permissions");
        assertThat(perms).containsEntry("packages", "write");
        assertThat(perms).containsEntry("id-token", "write");
        assertThat(perms).containsEntry("attestations", "write");

        // The digest is surfaced, and the SBOM attested against the pushed image
        // after the push rather than against something built alongside it.
        assertThat(body).contains("GITHUB_STEP_SUMMARY");
        assertThat(stepNames(wf, "native")).containsSubsequence("Push image", "Attest SBOM");
        Map<String, Object> attest = step(wf, "native", "Attest SBOM");
        assertThat(String.valueOf(attest.get("uses"))).contains("attest-sbom");
        assertThat(section(attest, "with"))
                .containsEntry("sbom-path", "target/classes/META-INF/sbom/application.cdx.json")
                .containsEntry("subject-digest", "${{ steps.push.outputs.digest }}");
    }

    @Test
    @SVCs({"SVC_GW_0108"})
    void releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing() throws IOException {
        Map<String, Object> wf = parse(REPO_ROOT.resolve(".github/workflows/release.yml"));

        // Dispatch only: a release is never a side effect of a push or a schedule.
        assertThat(triggers(wf)).containsOnlyKeys("workflow_dispatch");

        // The preview is the default, so previewing is free and shipping is the
        // deliberate act.
        Map<String, Object> inputs = section(section(triggers(wf), "workflow_dispatch"), "inputs");
        assertThat(section(inputs, "dry-run")).containsEntry("default", true);

        // The release-candidate dropdown, and the override a hand-entered version
        // needs before it may disagree with the derived one.
        assertThat(section(inputs, "prerelease").get("options")).isEqualTo(List.of("none", "rc", "b", "a"));
        assertThat(inputs).containsKey("force");

        // No `ref` input: `uses: ./...` resolves to the caller's ref, so a ref input
        // would tag one commit while the gates verified another.
        assertThat(inputs)
                .as("a ref input would decouple what is tested from what is tagged")
                .doesNotContainKey("ref");

        // The gates run before the approval, so the reviewer approves something
        // already green rather than a version string.
        assertThat(needsOf(wf, "tag")).contains("checks");
        assertThat(needsOf(wf, "approve")).contains("tag");

        // The gate guards the publish, not the tag.
        assertThat(section(job(wf, "approve"), "environment")).containsEntry("name", "stable");
        assertThat(job(wf, "tag"))
                .as("the tag job is deliberately not the gate")
                .doesNotContainKey("environment");

        // Every publish job waits on that approval.
        for (String publisher : List.of("image", "docs", "package")) {
            assertThat(needsOf(wf, publisher))
                    .as("%s waits on the approval", publisher)
                    .contains("approve");
        }

        // Created as a prerelease, promoted only after the published bytes are
        // verified, so nothing resolving "latest" sees an incomplete release.
        assertThat(String.valueOf(job(wf, "tag").get("uses"))).contains("common-release-tag.yml");
        assertThat(needsOf(wf, "verify")).contains("assets", "image");
        assertThat(needsOf(wf, "promote")).contains("verify");
        assertThat(String.valueOf(job(wf, "promote").get("uses"))).contains("common-release-promote.yml");

        // A candidate deliberately skips `docs`, so a skipped dependency must not
        // cascade into never promoting a real release.
        assertThat(String.valueOf(job(wf, "promote").get("if")))
                .as("promotion is guarded on nothing having failed, not on everything having succeeded")
                .contains("!contains(needs.*.result, 'failure')");
    }

    @Test
    @SVCs({"SVC_GW_0109"})
    void releaseTagsAreBareSemanticVersionsCutOnlyFromReachableUntaggedCommits() throws IOException {
        // No workflow filters on, or strips, a prefixed tag any more. Asserted
        // against the parsed triggers rather than the file text, so prose about the
        // rule cannot satisfy the rule.
        for (Path file : workflows()) {
            String name = file.getFileName().toString();
            Map<String, Object> push = section(triggers(parse(file)), "push");
            if (push != null) {
                assertThat(push).as("%s must not filter on tags", name).doesNotContainKey("tags");
            }
            assertThat(uncommented(Files.readString(file)))
                    .as("%s must not strip or match a v prefix", name)
                    .doesNotContain("GITHUB_REF_NAME#v")
                    .doesNotContain("refs/tags/v");
        }

        // The changelog config recognises only bare three-part versions as release
        // tags, anchored at both ends so neither a stray `v1.0.0` nor a release
        // candidate becomes a release boundary.
        assertThat(Files.readString(REPO_ROOT.resolve("cliff.toml")))
                .contains("tag_pattern = \"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+$\"");

        Map<String, Object> release = parse(REPO_ROOT.resolve(".github/workflows/release.yml"));

        // The reachability and already-released checks live in the commons, which
        // this workflow must actually call for them to run. `maven` is the format
        // whose validator rejects a v prefix.
        assertThat(String.valueOf(job(release, "prepare").get("uses"))).contains("common-release-prepare.yml");
        assertThat(section(job(release, "prepare"), "with")).containsEntry("version-format", "maven");

        // The tag is the only source of the released version: the chart carries a
        // placeholder, and the release stamps the real value from the tag.
        assertThat(Files.readString(REPO_ROOT.resolve("helm/skills-gateway/Chart.yaml")))
                .as("the chart must not carry a hand-maintained release version")
                .contains("version: 0.0.0-SNAPSHOT");
        String stamp = String.valueOf(
                step(release, "package", "Stamp and package the Helm chart").get("run"));
        assertThat(stamp).contains("--version \"$VERSION\"");
        assertThat(stamp).contains("--app-version \"$VERSION\"");

        // And the artifacts are built from the tag, not the branch, so what is
        // attached carries the version the tag names.
        Map<String, Object> checkout = steps(release, "package").getFirst();
        assertThat(section(checkout, "with"))
                .as("the package job checks out the tag, not the branch")
                .containsEntry("ref", "${{ needs.prepare.outputs.version }}");
    }

    // --- helpers -------------------------------------------------------------
    //
    // These tests assert on the parsed workflow rather than its text: an earlier
    // draft grepped for strings and three assertions passed or failed on this
    // file's own comments instead of on the YAML they described.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path file) throws IOException {
        return new Yaml().loadAs(Files.readString(file), Map.class);
    }

    /**
     * The `on:` block. YAML 1.1 resolves a bare `on` to boolean true, which is how SnakeYAML keys it,
     * so accept either form rather than depending on the parser's resolver.
     */
    private static Map<String, Object> triggers(Map<String, Object> workflow) {
        Map<String, Object> on = section(workflow, "on");
        return on != null ? on : section(workflow, Boolean.TRUE);
    }

    private static Map<String, Object> job(Map<String, Object> workflow, String id) {
        Map<String, Object> job = section(section(workflow, "jobs"), id);
        assertThat(job).as("job '%s' is present", id).isNotNull();
        return job;
    }

    /** A job's `needs`, normalised: the schema allows a bare string or a list. */
    private static List<String> needsOf(Map<String, Object> workflow, String id) {
        Object needs = job(workflow, id).get("needs");
        assertThat(needs).as("job '%s' declares needs", id).isNotNull();
        return needs instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(needs));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> workflow, String jobId) {
        return (List<Map<String, Object>>) job(workflow, jobId).get("steps");
    }

    private static List<String> stepNames(Map<String, Object> workflow, String jobId) {
        return steps(workflow, jobId).stream()
                .map(s -> String.valueOf(s.get("name")))
                .toList();
    }

    private static Map<String, Object> step(Map<String, Object> workflow, String jobId, String stepName) {
        return steps(workflow, jobId).stream()
                .filter(s -> stepName.equals(s.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step named '" + stepName + "' in job '" + jobId + "'"));
    }

    private static String stepCondition(Map<String, Object> workflow, String jobId, String stepName) {
        Object condition = step(workflow, jobId, stepName).get("if");
        assertThat(condition).as("step '%s' has a condition", stepName).isNotNull();
        return String.valueOf(condition).trim();
    }

    /** Comments stripped, so a rule cannot be satisfied -- or broken -- by prose describing it. */
    private static String uncommented(String yaml) {
        return yaml.lines().filter(line -> !line.stripLeading().startsWith("#")).collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, Object key) {
        if (parent == null) {
            return null;
        }
        Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static List<String> inputNames(Map<String, Object> workflow, String trigger) {
        Map<String, Object> inputs = section(section(triggers(workflow), trigger), "inputs");
        return inputs == null ? List.of() : List.copyOf(inputs.keySet());
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(REPO_ROOT.resolve(".github/workflows"))) {
            return files.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        }
    }
}
