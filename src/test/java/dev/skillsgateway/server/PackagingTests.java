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
    @SVCs({"SVC_GW_0120"})
    void chartRefusesToRenderWithoutAnExplicitStorageDurabilityChoice() throws IOException {
        Path chart = REPO_ROOT.resolve("helm/skills-gateway");
        Map<String, Object> persistence = section(parse(chart.resolve("values.yaml")), "persistence");

        // No default: the chart ships neither a durable nor an ephemeral choice,
        // so an install that says nothing gets nothing rendered.
        assertThat(persistence).as("persistence block present").isNotNull();
        assertThat(persistence.get("mode"))
                .as("the storage durability choice has no default")
                .isEqualTo("");
        assertThat(persistence.get("existingClaim")).isEqualTo("");

        // The Deployment delegates the volume source to the helper rather than
        // choosing one itself, so there is a single place the choice is made and
        // no second, silent path to an emptyDir.
        String deployment = Files.readString(chart.resolve("templates/deployment.yaml"));
        String dataVolume = between(deployment, "volumes:", "- name: tmp");
        assertThat(dataVolume).contains("skills-gateway.storageVolume");
        assertThat(dataVolume)
                .as("the data volume must not carry its own storage fallback")
                .doesNotContain("emptyDir")
                .doesNotContain("persistentVolumeClaim");

        String helper =
                define(Files.readString(chart.resolve("templates/_helpers.tpl")), "skills-gateway.storageVolume");

        // Exactly two accepted answers, and the ephemeral one is the only branch
        // that may produce an emptyDir.
        assertThat(helper).contains("eq $mode \"existingClaim\"").contains("eq $mode \"ephemeral\"");
        assertThat(branch(helper, "eq $mode \"ephemeral\"")).contains("emptyDir");
        assertThat(branch(helper, "eq $mode \"existingClaim\"")).contains("persistentVolumeClaim");

        // Durable storage that names no claim is refused too -- otherwise the
        // choice is made and still nothing durable is mounted.
        assertThat(branch(helper, "eq $mode \"existingClaim\""))
                .as("a claimless durable choice is refused")
                .contains("if not .Values.persistence.existingClaim")
                .contains("fail");

        // Anything else stops the render, and says why rather than only that.
        String otherwise = branch(helper, "else");
        assertThat(otherwise)
                .as("an unrecognised or absent choice fails the render")
                .contains("fail");
        assertThat(otherwise)
                .as("the refusal states the data-loss consequence")
                .contains("lost when the pod restarts")
                .contains("existingClaim")
                .contains("ephemeral");
    }

    @Test
    @SVCs({"SVC_GW_0121"})
    void chartCarriesRegistryCredentialsAnOptionalIngressAndDefaultReservations() throws IOException {
        Path chart = REPO_ROOT.resolve("helm/skills-gateway");
        Map<String, Object> values = parse(chart.resolve("values.yaml"));

        // Pull secrets: declarable, empty by default, and actually reaching the
        // pod spec -- a values key nothing reads would be worse than none.
        assertThat(values).containsKey("imagePullSecrets");
        assertThat(values.get("imagePullSecrets")).isEqualTo(List.of());
        String deployment = Files.readString(chart.resolve("templates/deployment.yaml"));
        assertThat(deployment).contains("imagePullSecrets:").contains(".Values.imagePullSecrets");

        // Reservations are declared, not left to the cluster's guess.
        Map<String, Object> resources = section(values, "resources");
        assertThat(section(resources, "requests")).containsKeys("cpu", "memory");
        assertThat(section(resources, "limits")).containsKeys("cpu", "memory");

        // The ingress is off unless asked for, and carries the full surface a
        // real one needs.
        Map<String, Object> ingress = section(values, "ingress");
        assertThat(ingress).as("ingress block present").isNotNull();
        assertThat(ingress.get("enabled")).as("the ingress is opt-in").isEqualTo(false);
        assertThat(ingress).containsKeys("className", "annotations", "hosts", "tls");

        String template = Files.readString(chart.resolve("templates/ingress.yaml"));
        assertThat(template).contains("if .Values.ingress.enabled");
        assertThat(template)
                .contains("kind: Ingress")
                .contains("ingressClassName")
                .contains(".Values.ingress.annotations")
                .contains(".Values.ingress.tls")
                .contains(".Values.ingress.hosts")
                .contains("pathType");

        // TLS is what keeps the facade's Basic-auth token off the wire, so the
        // values file has to say so where an operator configures the ingress.
        assertThat(Files.readString(chart.resolve("values.yaml")))
                .as("the values file explains why TLS is not optional in production")
                .contains("HTTP Basic");

        // Its own service account, nameable and annotatable, because that is
        // what a workload-identity binding attaches to.
        Map<String, Object> serviceAccount = section(values, "serviceAccount");
        assertThat(serviceAccount).as("serviceAccount block present").isNotNull();
        assertThat(serviceAccount.get("create")).isEqualTo(true);
        assertThat(serviceAccount).containsKeys("name", "annotations");
        assertThat(Files.readString(chart.resolve("templates/serviceaccount.yaml")))
                .contains("kind: ServiceAccount")
                .contains("if .Values.serviceAccount.create")
                .contains(".Values.serviceAccount.annotations");
        assertThat(deployment).contains("serviceAccountName:").contains("skills-gateway.serviceAccountName");

        // Least privilege by default: nothing here needs root, a capability or a
        // writable image.
        Map<String, Object> pod = section(values, "podSecurityContext");
        assertThat(pod.get("runAsNonRoot")).isEqualTo(true);
        assertThat(pod).containsKeys("runAsUser", "fsGroup");
        Map<String, Object> container = section(values, "securityContext");
        assertThat(container.get("allowPrivilegeEscalation")).isEqualTo(false);
        assertThat(container.get("readOnlyRootFilesystem")).isEqualTo(true);
        assertThat(section(container, "capabilities").get("drop")).isEqualTo(List.of("ALL"));
        assertThat(deployment).contains(".Values.podSecurityContext").contains(".Values.securityContext");

        // A read-only root filesystem still has to leave the runtime somewhere
        // to write, or the seal is just an outage.
        assertThat(deployment).contains("mountPath: /tmp").contains("- name: tmp\n          emptyDir: {}");
    }

    @Test
    @SVCs({"SVC_GW_0122"})
    void chartPassesArbitraryApplicationConfigurationThrough() throws IOException {
        Path chart = REPO_ROOT.resolve("helm/skills-gateway");
        Map<String, Object> values = parse(chart.resolve("values.yaml"));

        // Empty by default -- the passthrough adds nothing until it is used.
        assertThat(values.get("extraEnv")).isEqualTo(List.of());
        assertThat(values.get("extraEnvFrom")).isEqualTo(List.of());
        assertThat(values.get("config")).isEqualTo(Map.of());

        String deployment = Files.readString(chart.resolve("templates/deployment.yaml"));

        // Single-valued settings and secret references land in the container's
        // env verbatim, so `valueFrom` works without a chart change.
        // Matched with the closing brace: `.Values.extraEnv` alone is a prefix
        // of `.Values.extraEnvFrom`, so the looser assertion stayed green with
        // the extraEnv block deleted.
        assertThat(deployment).contains(".Values.extraEnv }}");
        assertThat(deployment).contains("envFrom:").contains(".Values.extraEnvFrom }}");

        // Structured configuration is a mounted file layered over the image's
        // own application.yaml, which is what makes the nested estate lists
        // expressible at all.
        assertThat(deployment)
                .contains("SPRING_CONFIG_ADDITIONAL_LOCATION")
                .contains("optional:file:/etc/skills-gateway/")
                .contains("mountPath: /etc/skills-gateway");
        assertThat(deployment).contains("skills-gateway.configMapName");

        // The estate is reconciled at startup, so a configuration change that
        // does not change the pod spec would never be read.
        assertThat(deployment)
                .as("a configuration change must roll the pods")
                .contains("checksum/config")
                .contains("sha256sum");

        String configMap = Files.readString(chart.resolve("templates/configmap.yaml"));
        assertThat(configMap)
                .contains("kind: ConfigMap")
                .contains("application.yaml: |")
                .contains(".Values.config");
    }

    @Test
    @SVCs({"SVC_GW_0115"})
    void chartRefusesAStorageShapeTheGatewayCannotHonour() throws IOException {
        Path chart = REPO_ROOT.resolve("helm/skills-gateway");
        Map<String, Object> values = parse(chart.resolve("values.yaml"));
        String helpers = Files.readString(chart.resolve("templates/_helpers.tpl"));
        String deployment = Files.readString(chart.resolve("templates/deployment.yaml"));

        // The backend is named in the chart exactly as it is named in the gateway, and defaults
        // the same way, so an upgrade of an existing install changes nothing.
        Map<String, Object> storage = section(values, "storage");
        assertThat(storage).as("storage block present").isNotNull();
        assertThat(storage.get("backend")).isEqualTo("filesystem");
        Map<String, Object> objectStore = section(storage, "objectStore");
        assertThat(objectStore).containsKeys("endpoint", "region", "bucket", "prefix");
        Map<String, Object> credentials = section(objectStore, "credentials");
        assertThat(credentials.get("mode"))
                .as("workload identity is the primary mechanism, not the fallback")
                .isEqualTo("web-identity");
        assertThat(credentials).containsKey("existingSecret");

        // Selecting the bucket must actually reach the gateway, or the chart would render a
        // deployment that reads from a filesystem while the values file says object storage.
        String gate = define(helpers, "skills-gateway.storageGate");
        assertThat(gate).as("the object-store selection is gated").contains("object-store");
        assertThat(gate)
                .as("an incomplete object-store selection is refused at render, not at startup")
                .contains(".Values.storage.objectStore")
                .contains("bucket is empty")
                .contains("region is empty")
                .contains("fail");
        assertThat(gate)
                .as("static credentials without the secret holding them are refused")
                .contains("existingSecret");
        assertThat(deployment).contains("skills-gateway.storageGate");
        assertThat(deployment)
                .contains("SKILLSGATEWAY_STORAGE_BACKEND")
                .contains("SKILLSGATEWAY_STORAGE_OBJECTSTORE_BUCKET")
                .contains("SKILLSGATEWAY_STORAGE_OBJECTSTORE_REGION")
                .contains("SKILLSGATEWAY_STORAGE_OBJECTSTORE_CREDENTIALS_MODE");
        assertThat(deployment)
                .as("a static access key comes from a Secret, never from the values file")
                .contains("access-key-id")
                .contains("secretKeyRef");
        assertThat(Files.readString(chart.resolve("values.yaml")))
                .as("the values file must not carry a place to type an access key")
                .doesNotContain("accessKeyId");

        // The third durability mode: no volume at all, and only where the bucket is the repository.
        String volume = define(helpers, "skills-gateway.storageVolume");
        assertThat(volume).contains("eq $mode \"none\"");
        assertThat(branch(volume, "eq $mode \"none\""))
                .as("no durable volume is only ever correct when the bucket holds the repositories")
                .contains("object-store")
                .contains("fail");
        assertThat(deployment)
                .as("the data volume and its mount are omitted when there is no volume to mount")
                .contains("ne .Values.persistence.mode \"none\"");
        assertThat(deployment)
                .as("with no volume the local pack cache still needs somewhere writable")
                .contains("SKILLSGATEWAY_STORAGE_OBJECTSTORE_CACHE_DIR");

        // Replica gating: the storage obstacle and the uncoordinated singletons, together.
        String replicas = define(helpers, "skills-gateway.replicaGate");
        assertThat(replicas).as("the replica gate exists").isNotEmpty();
        assertThat(replicas).contains("gt $replicas 1").contains("fail");
        assertThat(replicas)
                .as("more than one writer is refused outright on the filesystem backend")
                .contains("object-store");
        for (String poller : List.of("sync", "revet", "retention", "webhooks", "audit-export")) {
            assertThat(replicas)
                    .as("scaling out must refuse to duplicate the %s singleton", poller)
                    .contains(poller);
        }
        assertThat(deployment).contains("skills-gateway.replicaGate");
    }

    @Test
    @SVCs({"SVC_GW_0072"})
    void releaseWorkflowCarriesThePublishByDigestContract() throws IOException {
        Path file = REPO_ROOT.resolve(".github/workflows/native.yml");
        Map<String, Object> wf = parse(file);
        String body = Files.readString(file);

        // Publishes to the project namespace, by the released version. There is no
        // per-commit or moving tag any more: only release.yml ever reaches GHCR.
        assertThat(body).contains("ghcr.io/skillsgateway/skillsgateway");

        // The version is an input, never parsed out of a ref.
        assertThat(triggers(wf)).containsKey("workflow_call");
        assertThat(inputNames(wf, "workflow_call")).contains("version");
        assertThat(body).as("the version is never derived from a ref name").doesNotContain("github.ref_name");

        // A hand-pushed tag must not publish: no tag filter survives on the push
        // trigger, and a plain push builds and smoke-tests without publishing
        // anything either way.
        Map<String, Object> push = section(triggers(wf), "push");
        assertThat(push).as("push trigger present").isNotNull();
        assertThat(push).as("a hand-pushed tag must not publish").doesNotContainKey("tags");
        assertThat(push.get("branches")).isEqualTo(List.of("main"));

        // Every publish step is reachable only from the release workflow. Under
        // workflow_call `github.event_name` is the CALLER's event
        // (workflow_dispatch), so publication keys off `inputs.version` alone --
        // a main push, a schedule and a bare dispatch all leave it empty.
        for (String step : List.of("Log in to GHCR", "Push image by digest", "Attest SBOM")) {
            assertThat(stepCondition(wf, "native", step))
                    .as("%s is gated to the release workflow only", step)
                    .isEqualTo("inputs.version != ''");
        }

        // Permissions publishing and attestation require.
        Map<String, Object> perms = section(job(wf, "native"), "permissions");
        assertThat(perms).containsEntry("packages", "write");
        assertThat(perms).containsEntry("id-token", "write");
        assertThat(perms).containsEntry("attestations", "write");

        // The digest is surfaced, and the SBOM attested against the pushed image
        // after the push rather than against something built alongside it.
        assertThat(body).contains("GITHUB_STEP_SUMMARY");
        assertThat(stepNames(wf, "native")).containsSubsequence("Push image by digest", "Attest SBOM");
        Map<String, Object> attest = step(wf, "native", "Attest SBOM");
        assertThat(String.valueOf(attest.get("uses"))).contains("attest-sbom");
        assertThat(section(attest, "with"))
                .containsEntry("sbom-path", "target/classes/META-INF/sbom/application.cdx.json")
                .containsEntry("subject-digest", "${{ steps.push.outputs.digest }}");

        // Multi-arch: native-image cannot cross-compile, so each platform is a real
        // leg on its own runner rather than a buildx target reusing one build.
        Map<String, Object> strategy = section(job(wf, "native"), "strategy");
        assertThat(strategy).as("the native job is a matrix").isNotNull();
        Map<String, Object> matrix = section(strategy, "matrix");
        List<Map<String, Object>> legs = matrixLegs(matrix);
        assertThat(legs.stream().map(l -> l.get("platform")))
                .as("both platforms are built")
                .containsExactlyInAnyOrder("linux/amd64", "linux/arm64");
        assertThat(legs.stream().map(l -> l.get("runner")))
                .as("arm64 gets a real arm64 runner, not emulation")
                .contains("ubuntu-24.04-arm");
        assertThat(String.valueOf(job(wf, "native").get("runs-on"))).contains("matrix.runner");

        // Each leg pushes its own platform manifest addressed by digest only --
        // never a human-readable arch-suffixed tag, which is what keeps GHCR's
        // tagged-versions listing to one entry per release rather than three.
        Map<String, Object> pushStep = step(wf, "native", "Push image by digest");
        String pushRun = String.valueOf(pushStep.get("run"));
        assertThat(pushRun).contains("push-by-digest=true").contains("name-canonical=true");
        assertThat(pushRun)
                .as("the per-leg push must not create an arch-suffixed tag")
                .doesNotContain("-amd64\"")
                .doesNotContain("-arm64\"");
        assertThat(stepNames(wf, "native")).contains("Upload digest");

        // A downstream job combines the two per-arch digests into the published
        // multi-arch index under the released version's tag, gated exactly like
        // the per-leg publish steps -- restated rather than inherited, because it
        // is a separate job.
        Map<String, Object> publish = job(wf, "publish");
        assertThat(needsOf(wf, "publish")).contains("native");
        assertThat(String.valueOf(publish.get("if")))
                .as("the combine job repeats the publish gate")
                .contains("needs.native.result == 'success'")
                .contains("inputs.version != ''");
        assertThat(section(publish, "permissions")).containsEntry("packages", "write");
        String publishBody = String.valueOf(steps(wf, "publish").stream()
                .map(s -> s.get("run"))
                .filter(java.util.Objects::nonNull)
                .toList());
        assertThat(publishBody)
                .as("the combine job builds the index from the two downloaded digests")
                .contains("imagetools create")
                .contains("digests/amd64.txt")
                .contains("digests/arm64.txt");
    }

    @Test
    @SVCs({"SVC_GW_0162"})
    void ghcrCleanupWorkflowIsDispatchOnlyAndProtectsReleaseTags() throws IOException {
        Map<String, Object> wf = parse(REPO_ROOT.resolve(".github/workflows/ghcr-cleanup.yml"));

        // Dispatch-only: never a push, never a schedule.
        assertThat(triggers(wf)).containsOnlyKeys("workflow_dispatch");

        // Dry-run previews by default; deleting is the deliberate opt-out.
        Map<String, Object> inputs = section(section(triggers(wf), "workflow_dispatch"), "inputs");
        assertThat(section(inputs, "dry-run")).containsEntry("default", true);

        Map<String, Object> cleanup = job(wf, "cleanup");
        assertThat(section(cleanup, "permissions")).containsEntry("packages", "write");

        Map<String, Object> action = steps(wf, "cleanup").getFirst();
        assertThat(String.valueOf(action.get("uses"))).contains("ghcr-cleanup-action");
        Map<String, Object> with = section(action, "with");
        assertThat(with).containsEntry("package", "skillsgateway");
        assertThat(String.valueOf(with.get("delete-tags"))).contains("sha-*").contains("latest");
        // Release and release-candidate tags (GW_0109) are the only thing this
        // must never be able to delete.
        assertThat(String.valueOf(with.get("exclude-tags"))).contains("[0-9]*.[0-9]*.[0-9]*");
        assertThat(with).containsEntry("dry-run", "${{ inputs.dry-run }}");
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
        assertThat(needsOf(wf, "approve")).contains("checks");

        // And the approval decides whether the version comes into existence: the
        // tag is the first irreversible, publicly visible step, so it is behind
        // the gate rather than in front of it.
        assertThat(needsOf(wf, "tag"))
                .as("the approval must be able to prevent the tag")
                .contains("approve");

        // The gate is the approve job. The tag job is behind it, not it.
        assertThat(section(job(wf, "approve"), "environment")).containsEntry("name", "stable");
        assertThat(job(wf, "tag"))
                .as("the tag job is deliberately not the gate")
                .doesNotContainKey("environment");

        // Nothing publishes before the tag, which is itself behind the approval.
        for (String publisher : List.of("image", "docs", "package")) {
            assertThat(needsOf(wf, publisher))
                    .as("%s waits on the tag", publisher)
                    .contains("tag");
        }

        // A run that tags and then stops leaves a partial release and says so —
        // including when it stops by being cancelled, which `verify` cannot
        // report because a failed publish skips it.
        assertThat(needsOf(wf, "partial")).contains("tag", "promote");
        assertThat(String.valueOf(job(wf, "partial").get("if")))
                .as("the partial report must survive a cancelled run and key off the tag")
                .contains("always()")
                .contains("needs.tag.result == 'success'");

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

    /** The text between two markers, both of which must be present in that order. */
    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as("'%s' is present", from).isNotNegative();
        int end = text.indexOf(to, start);
        assertThat(end).as("'%s' follows '%s'", to, from).isNotNegative();
        return text.substring(start, end);
    }

    /** The body of one `{{- define "name" -}}` block, up to the next define or the end of the file. */
    private static String define(String template, String name) {
        int start = template.indexOf("define \"" + name + "\"");
        assertThat(start).as("template '%s' is defined", name).isNotNegative();
        int next = template.indexOf("{{- define", start + 1);
        return next < 0 ? template.substring(start) : template.substring(start, next);
    }

    /**
     * One branch of a template's if/else-if/else chain: the first part introduced by {@code marker},
     * or the trailing `else` when marker is "else". Asserting on the branch rather than on the whole
     * body is what keeps "an emptyDir is produced" tied to "and only when ephemeral was asked for".
     */
    private static String branch(String template, String marker) {
        String[] parts = template.split("\\{\\{- else");
        if ("else".equals(marker)) {
            return parts[parts.length - 1];
        }
        for (String part : parts) {
            if (part.contains(marker)) {
                return part;
            }
        }
        throw new AssertionError("no branch introduced by '" + marker + "'");
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

    /** A `strategy.matrix.include` list, normalised to a list of leg maps. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> matrixLegs(Map<String, Object> matrix) {
        assertThat(matrix).as("matrix present").isNotNull();
        Object include = matrix.get("include");
        assertThat(include).as("matrix.include present").isNotNull();
        return (List<Map<String, Object>>) include;
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
