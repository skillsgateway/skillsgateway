package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Verification of the {@code skill-conformance} connector inside the real chain (GW_INGEST_0028), under
 * the default — advisory — posture. The blocking half lives in {@link SkillConformanceEnforceTests},
 * which runs its own Spring context: the posture is a deployment decision, deliberately not
 * settable per call, which is what makes it attributable per chain run.
 */
class SkillConformanceTests extends AbstractGatewayTest {

    private static final String CONNECTOR = "skill-conformance";

    private static final String BROKEN_SKILL = "plugins/hello/skills/broken/SKILL.md";

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private VettingService vettingService;

    @Test
    @SVCs({"SVC_GW_INGEST_0028"})
    void aConformantMarketplacePassesAndTheRunNamesThePinnedSpecification() throws Exception {
        Registered registered = registerAndIngest(uniqueName("confok"), createUpstream(DEFAULT_MANIFEST));

        VettingRepository.Run run =
                vettingRepository.latestRun(registered.snapshot().id()).orElseThrow();
        VettingRepository.VerdictView verdict = verdictOf(run);

        assertThat(verdict.state()).isEqualTo(VerdictState.PASS);
        assertThat(verdict.findings()).isEmpty();
        // A clean pass states what it examined (GW_VETTING_0023), including the pin it examined against.
        assertThat(verdict.detail()).contains("scanned 1 SKILL.md file(s)").contains("agentskills-2026-08-04");

        // The pinned specification is part of the recorded chain identity, so a bump is attributable.
        assertThat(run.chain()).contains(CONNECTOR + "@agentskills-2026-08-04+schema-");
        assertThat(run.chain()).contains("+advisory");

        // The connector is in the chain the API advertises, not only in the run.
        assertThat(vettingService.connectors().stream()
                        .map(dev.skillsgateway.server.vetting.VettingConnector::name)
                        .toList())
                .contains(CONNECTOR);
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0028"})
    void aNonConformantSkillWarnsWithAnActionableFindingAndDoesNotBlockApproval() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("confbad"),
                createUpstream(
                        DEFAULT_MANIFEST,
                        Map.of(BROKEN_SKILL, "---\nname: Broken\n---\n# Broken\n\nNo description.\n")));
        long snapshotId = registered.snapshot().id();

        VettingRepository.VerdictView verdict =
                verdictOf(vettingRepository.latestRun(snapshotId).orElseThrow());
        assertThat(verdict.state()).isEqualTo(VerdictState.WARN);

        // Every finding names the file and says what to fix — the point of the connector.
        assertThat(verdict.findings()).allSatisfy(finding -> {
            assertThat(finding.location()).isEqualTo(BROKEN_SKILL);
            assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
        });
        assertThat(verdict.findings()).extracting(Finding::id).contains("skill-field-missing", "skill-field-invalid");
        assertThat(verdict.findings())
                .extracting(Finding::message)
                .anySatisfy(message -> assertThat(message).contains("'description'"))
                .anySatisfy(message -> assertThat(message).contains("must be lowercase"))
                .anySatisfy(message -> assertThat(message).contains("the skill directory is 'broken'"));

        // Advisory means advisory: the reviewer sees all of it and the marketplace still ships.
        assertThat(approve(snapshotId).state()).isEqualTo(Snapshot.APPROVED);

        // Deterministic: a second run over the same pinned content says exactly the same thing.
        vettingService.run(registered.snapshot(), registered.marketplace().name(), "revet-manual");
        assertThat(verdictOf(vettingRepository.latestRun(snapshotId).orElseThrow())
                        .findings())
                .containsExactlyInAnyOrderElementsOf(verdict.findings());
    }

    @Test
    @SVCs({"SVC_GW_INGEST_0028"})
    void hostileFrontmatterIsAFindingAndNeverAnErroredConnector() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("confbomb"),
                createUpstream(
                        DEFAULT_MANIFEST,
                        Map.of(BROKEN_SKILL, "---\ndeep: %s%s\n---\n".formatted("[".repeat(200), "]".repeat(200)))));

        VettingRepository.VerdictView verdict = verdictOf(
                vettingRepository.latestRun(registered.snapshot().id()).orElseThrow());

        // An ERROR verdict here would mean the connector threw and the chain fell back to blocking;
        // a hostile document has to be an ordinary finding about the content instead.
        assertThat(verdict.state()).isEqualTo(VerdictState.WARN);
        assertThat(verdict.findings()).extracting(Finding::id).containsExactly("skill-frontmatter-malformed");
    }

    private static VettingRepository.VerdictView verdictOf(VettingRepository.Run run) {
        List<VettingRepository.VerdictView> verdicts = run.verdicts().stream()
                .filter(candidate -> candidate.connector().equals(CONNECTOR))
                .toList();
        assertThat(verdicts).as("verdict of connector '%s'", CONNECTOR).hasSize(1);
        return verdicts.getFirst();
    }
}
