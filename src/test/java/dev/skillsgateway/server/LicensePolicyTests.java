package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.LicenseScanConnector;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verification of the configured license policy (GW_0094) and its surfacing on the report endpoint
 * (GW_0095). Its own Spring context via {@link TestPropertySource}: the allow/ban lists are a
 * deployment decision, deliberately not settable per call — which is exactly what makes them
 * attributable per chain run.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.vetting.license.allowed=MIT,Apache-2.0",
            "skills-gateway.vetting.license.banned=AGPL-3.0"
        })
class LicensePolicyTests extends AbstractGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private SkillsGatewayProperties properties;

    @Test
    @SVCs({"SVC_GW_0094"})
    void aBannedLicenseBlocksApprovalThroughTheStandardGateUntilWaived() throws Exception {
        String name = uniqueName("licban");
        Registered registered =
                registerAndIngest(name, createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.AGPL_3_0)));
        long snapshotId = registered.snapshot().id();

        // The violation is an ordinary blocking finding: the standard refusal names the connector
        // and the uncovered rule, exactly as it does for a planted credential.
        assertThatThrownBy(() -> approvalService.approve(snapshotId, "alice"))
                .isInstanceOf(VettingBlockedException.class)
                .satisfies(thrown -> {
                    assertThat(((VettingBlockedException) thrown).blockingConnectors())
                            .contains("license-scan");
                    assertThat(((VettingBlockedException) thrown).uncoveredFindings())
                            .extracting(WaiverEvaluation.UncoveredFinding::ruleId)
                            .contains("license-banned");
                });
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);

        VettingRepository.Run run = vettingRepository.latestRun(snapshotId).orElseThrow();
        assertThat(findings(run, "license-banned")).singleElement().satisfies(finding -> {
            assertThat(finding.location()).isEqualTo("LICENSE");
            assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(finding.message()).contains("AGPL-3.0");
        });
        // The policy in force is attributable from the run itself (GW_0049): the connector version
        // carries a digest of the lists, so this chain identity differs from a default deployment.
        assertThat(run.chain()).contains("license-scan@");
        String defaultVersion = new LicenseScanConnector(new SkillsGatewayProperties(
                        null, null, null, null, null, null, null, null, null, null, null, null))
                .version();
        String configuredVersion =
                run.chain().lines().findFirst().orElseThrow().replaceAll(".*license-scan@([^,]*).*", "$1");
        assertThat(configuredVersion).isNotEqualTo(defaultVersion);

        // And on the ledger, like every other verdict.
        assertThat(fetchLogRepository.list())
                .filteredOn(
                        entry -> name.equals(entry.get("marketplace")) && "vetting-verdict".equals(entry.get("event")))
                .extracting(entry -> String.valueOf(entry.get("detail")))
                .contains("license-scan=fail");

        // The standard acceptance path — a scoped, expiring waiver on the rule id — is the only
        // way through, and it is enough.
        waiverService.create(
                snapshotId,
                "license-banned",
                WaiverScope.SNAPSHOT,
                null,
                "legal approved this single AGPL snapshot",
                Instant.now().plus(Duration.ofDays(7)),
                "alice");
        assertThat(approvalService.approve(snapshotId, "alice").snapshot().state())
                .isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0094"})
    void theAllowListBlocksAbsentUnknownAndMissingLicensesAndPassesListedOnes() throws Exception {
        // Not on the allow list (and not banned): blocked as license-not-allowed.
        Registered gpl = registerAndIngest(
                uniqueName("licgpl"), createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.GPL_3_0)));
        assertUncovered(gpl.snapshot().id(), "license-not-allowed");

        // Unknown license: its own state, and blocking once an allow list is configured.
        Registered unknown = registerAndIngest(
                uniqueName("licunk"),
                createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.UNRECOGNIZABLE)));
        assertUncovered(unknown.snapshot().id(), "license-unknown");

        // No license information at all: likewise blocking under an allow list.
        Registered missing = registerAndIngest(uniqueName("licnone"), createUpstream(DEFAULT_MANIFEST));
        assertUncovered(missing.snapshot().id(), "license-missing");

        // A listed license sails through: detection is informational, nothing blocks.
        Registered mit = registerAndIngest(
                uniqueName("licmit"), createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.MIT)));
        assertThat(approve(mit.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0095"})
    void theLicenseEndpointMarksTheBannedLicenseUnderTheConfiguredPolicy() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("licbanapi"), createUpstream(DEFAULT_MANIFEST, Map.of("LICENSE", LicenseFixtures.AGPL_3_0)));

        String body = mockMvc.perform(get("/api/snapshots/%d/licenses"
                                .formatted(registered.snapshot().id()))
                        .with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode report = MAPPER.readTree(body);

        JsonNode entry = report.path("licenses").get(0);
        assertThat(entry.path("spdxId").asText()).isEqualTo("AGPL-3.0");
        assertThat(entry.path("evaluation").asText()).isEqualTo("BANNED");
        assertThat(MAPPER.convertValue(report.path("allowed"), List.class)).containsExactly("MIT", "Apache-2.0");
        assertThat(MAPPER.convertValue(report.path("banned"), List.class)).containsExactly("AGPL-3.0");
    }

    private void assertUncovered(long snapshotId, String ruleId) {
        assertThatThrownBy(() -> approvalService.approve(snapshotId, "alice"))
                .isInstanceOf(VettingBlockedException.class)
                .satisfies(thrown -> assertThat(((VettingBlockedException) thrown).uncoveredFindings())
                        .extracting(WaiverEvaluation.UncoveredFinding::ruleId)
                        .contains(ruleId));
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);
    }

    private static List<dev.skillsgateway.server.vetting.Finding> findings(VettingRepository.Run run, String ruleId) {
        return run.verdicts().stream()
                .filter(verdict -> "license-scan".equals(verdict.connector()))
                .flatMap(verdict -> verdict.findings().stream())
                .filter(finding -> finding.id().equals(ruleId))
                .toList();
    }
}
