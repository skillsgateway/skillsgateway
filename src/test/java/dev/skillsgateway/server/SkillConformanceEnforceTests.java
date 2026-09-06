package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verification of the enforcing posture of the {@code skill-conformance} connector (GW_0167). Its
 * own Spring context via {@link TestPropertySource}: the posture is a deployment decision, not
 * settable per call, which is exactly what makes it attributable per chain run.
 *
 * <p>The advisory default — the posture an operator gets by upgrading — is verified in
 * {@link SkillConformanceTests}.
 */
@TestPropertySource(properties = {"skills-gateway.vetting.conformance.enforce=true"})
class SkillConformanceEnforceTests extends AbstractGatewayTest {

    private static final String BROKEN_SKILL = "plugins/hello/skills/broken/SKILL.md";

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private WaiverService waiverService;

    @Test
    @SVCs({"SVC_GW_0167"})
    void underEnforcementAConformanceDefectBlocksApprovalUntilItIsWaived() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("confenf"),
                createUpstream(DEFAULT_MANIFEST, Map.of(BROKEN_SKILL, "---\nname: broken\n---\n# Broken\n")));
        long snapshotId = registered.snapshot().id();

        // The defect is an ordinary blocking finding: the standard refusal names the connector and
        // the uncovered rule, exactly as it does for a planted credential.
        assertThatThrownBy(() -> approvalService.approve(snapshotId, "alice"))
                .isInstanceOf(VettingBlockedException.class)
                .satisfies(thrown -> {
                    assertThat(((VettingBlockedException) thrown).blockingConnectors())
                            .contains("skill-conformance");
                    assertThat(((VettingBlockedException) thrown).uncoveredFindings())
                            .extracting(WaiverEvaluation.UncoveredFinding::ruleId)
                            .contains("skill-field-missing");
                });
        assertThat(snapshotRepository.findById(snapshotId).orElseThrow().state())
                .isEqualTo(Snapshot.HELD);

        VettingRepository.Run run = vettingRepository.latestRun(snapshotId).orElseThrow();
        assertThat(run.verdicts())
                .filteredOn(verdict -> "skill-conformance".equals(verdict.connector()))
                .singleElement()
                .satisfies(verdict -> {
                    assertThat(verdict.state()).isEqualTo(VerdictState.FAIL);
                    assertThat(verdict.findings()).allSatisfy(finding -> assertThat(finding.severity())
                            .isEqualTo(Severity.HIGH));
                });
        // The posture is in the recorded chain identity, so this run is distinguishable from a
        // default deployment's run over the same content.
        assertThat(run.chain()).contains("skill-conformance@").contains("+enforce");

        // The standard acceptance path — a scoped, expiring waiver on the rule id — is the only way
        // through, and it is enough.
        waiverService.create(
                snapshotId,
                "skill-field-missing",
                WaiverScope.SNAPSHOT,
                null,
                "publisher is fixing the frontmatter in the next release",
                Instant.now().plus(Duration.ofDays(7)),
                "alice");
        assertThat(approvalService.approve(snapshotId, "alice").snapshot().state())
                .isEqualTo(Snapshot.APPROVED);
    }

    @Test
    @SVCs({"SVC_GW_0167"})
    void underEnforcementAConformantMarketplaceIsStillApprovedWithoutCeremony() throws Exception {
        Registered registered = registerAndIngest(uniqueName("confenfok"), createUpstream(DEFAULT_MANIFEST));

        assertThat(approve(registered.snapshot().id()).state()).isEqualTo(Snapshot.APPROVED);
    }
}
