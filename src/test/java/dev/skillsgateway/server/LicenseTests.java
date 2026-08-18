package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.VettingService;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verification of deterministic license detection (GW_0093) and the default — warn-only — policy
 * behavior (GW_0094), plus the license report endpoint under the default policy (GW_0095). The
 * configured-policy half of GW_0094/GW_0095 lives in {@link LicensePolicyTests}, which runs its own
 * Spring context: the policy is a deployment decision, not settable per call.
 */
class LicenseTests extends AbstractGatewayTest {

    @Autowired
    private VettingService vettingService;

    @Autowired
    private VettingRepository vettingRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SVCs({"SVC_GW_0093"})
    void licensesAreDetectedDeterministicallyFromFilesTagsAndManifest() throws Exception {
        String name = uniqueName("licdetect");
        Registered registered = registerAndIngest(
                name,
                createUpstream(
                        LicenseFixtures.MANIFEST_WITH_LICENSES,
                        Map.of(
                                "LICENSE", LicenseFixtures.MIT,
                                "plugins/hello/COPYING", LicenseFixtures.APACHE_2_0,
                                "plugins/hello/skills/hello/LICENSE.txt", LicenseFixtures.SPDX_TAG_ONLY)));

        List<Finding> detections = findingsOf(registered.snapshot().id(), "license-detected");

        // Every source is seen: the root license file, a nested COPYING, a file identified only by
        // its SPDX tag, and the manifest metadata fields.
        assertThat(detections)
                .extracting(Finding::location)
                .containsExactlyInAnyOrder(
                        "LICENSE",
                        "plugins/hello/COPYING",
                        "plugins/hello/skills/hello/LICENSE.txt",
                        ".claude-plugin/marketplace.json#license",
                        ".claude-plugin/marketplace.json#plugins[hello].license");
        assertThat(messageOf(detections, "LICENSE")).contains("MIT");
        assertThat(messageOf(detections, "plugins/hello/COPYING")).contains("Apache-2.0");
        assertThat(messageOf(detections, "plugins/hello/skills/hello/LICENSE.txt"))
                .contains("BSD-3-Clause");
        assertThat(messageOf(detections, ".claude-plugin/marketplace.json#license"))
                .contains("MIT");
        assertThat(messageOf(detections, ".claude-plugin/marketplace.json#plugins[hello].license"))
                .contains("ISC");
        // Detection is recorded, not judged: identified licenses are informational under defaults.
        assertThat(detections).allSatisfy(finding -> assertThat(finding.severity()).isEqualTo(Severity.INFO));
        assertThat(verdictOf(registered.snapshot().id(), "license-scan").state())
                .isEqualTo(VerdictState.PASS);

        // Deterministic: a second run over the same pinned content yields the same findings.
        vettingService.run(registered.snapshot(), name, "revet-manual");
        assertThat(findingsOf(registered.snapshot().id(), "license-detected"))
                .containsExactlyInAnyOrderElementsOf(detections);
    }

    @Test
    @SVCs({"SVC_GW_0093", "SVC_GW_0094"})
    void anUnrecognizableLicenseIsUnknownAndWarnsWithoutBlockingUnderDefaults() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("licunknown"),
                createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.UNRECOGNIZABLE)));

        assertThat(findingsOf(registered.snapshot().id(), "license-unknown"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.location()).isEqualTo("LICENSE");
                    assertThat(finding.severity()).isEqualTo(Severity.LOW);
                });
        // A warning, not a block: with no policy configured, approval goes through.
        assertThat(verdictOf(registered.snapshot().id(), "license-scan").state())
                .isEqualTo(VerdictState.WARN);
        assertThat(approve(registered.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0093", "SVC_GW_0094"})
    void aSnapshotWithoutAnyLicenseInformationIsMissingAndWarnsUnderDefaults() throws Exception {
        Registered registered = registerAndIngest(uniqueName("licmissing"), createUpstream(DEFAULT_MANIFEST));

        assertThat(findingsOf(registered.snapshot().id(), "license-missing"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.LOW));
        assertThat(verdictOf(registered.snapshot().id(), "license-scan").state())
                .isEqualTo(VerdictState.WARN);
        assertThat(approve(registered.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0095"})
    void theLicenseEndpointReportsEveryDetectionAndAnswersNotFoundForAMissingSnapshot() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("licapi"),
                createUpstream(
                        LicenseFixtures.MANIFEST_WITH_LICENSES,
                        Map.of("LICENSE", LicenseFixtures.MIT, "plugins/hello/COPYING", LicenseFixtures.GIBBERISH)));

        String body = mockMvc.perform(get("/api/snapshots/%d/licenses"
                                .formatted(registered.snapshot().id()))
                        .with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode report = MAPPER.readTree(body);

        assertThat(report.path("snapshotId").asLong())
                .isEqualTo(registered.snapshot().id());
        assertThat(report.path("sha").asText()).isEqualTo(registered.snapshot().sha());
        assertThat(report.path("licenses").size()).isGreaterThan(0);
        assertThat(entry(report, "LICENSE").path("spdxId").asText()).isEqualTo("MIT");
        assertThat(entry(report, "LICENSE").path("source").asText()).isEqualTo("file");
        assertThat(entry(report, "LICENSE").path("evaluation").asText()).isEqualTo("OK");
        // The unknown state is first-class on the wire too.
        assertThat(entry(report, "plugins/hello/COPYING").path("spdxId").isNull())
                .isTrue();
        assertThat(entry(report, "plugins/hello/COPYING").path("evaluation").asText())
                .isEqualTo("UNKNOWN");
        assertThat(entry(report, ".claude-plugin/marketplace.json#plugins[hello].license")
                        .path("declared")
                        .asText())
                .isEqualTo("ISC");
        // No policy configured: the report says so rather than inventing one.
        assertThat(report.path("allowed")).isEmpty();
        assertThat(report.path("banned")).isEmpty();

        mockMvc.perform(get("/api/snapshots/999999999/licenses").with(oidcLogin()))
                .andExpect(status().isNotFound());
    }

    private static JsonNode entry(JsonNode report, String location) {
        for (JsonNode license : report.path("licenses")) {
            if (location.equals(license.path("location").asText())) {
                return license;
            }
        }
        throw new AssertionError("no license entry at " + location);
    }

    private static String messageOf(List<Finding> findings, String location) {
        return findings.stream()
                .filter(finding -> location.equals(finding.location()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding at " + location))
                .message();
    }

    private VettingRepository.VerdictView verdictOf(long snapshotId, String connector) {
        VettingRepository.Run run = vettingRepository.latestRun(snapshotId).orElseThrow();
        return run.verdicts().stream()
                .filter(candidate -> candidate.connector().equals(connector))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no verdict of connector " + connector));
    }

    private List<Finding> findingsOf(long snapshotId, String ruleId) {
        return verdictOf(snapshotId, "license-scan").findings().stream()
                .filter(finding -> finding.id().equals(ruleId))
                .toList();
    }
}
