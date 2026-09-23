package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.approval.NameCollisionGate;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A chain run is a function of pinned content and chain identity alone (GW_VETTING_0039): what else
 * the estate holds — including names that collide with this snapshot's — never reaches it.
 */
class ChainPurityTests extends AbstractNameCollisionTest {

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private RevetService revetService;

    @Autowired
    private WaiverService waiverService;

    @Test
    @SVCs({"SVC_GW_VETTING_0039"})
    void a_rerun_over_unchanged_content_is_unchanged_by_what_the_estate_did_meanwhile() throws Exception {
        String name = uniquePlugin();
        Registered subject = approved(name);
        VettingRepository.Run before =
                vettingRepository.latestRun(subject.snapshot().id()).orElseThrow();

        // The estate changes around it: a colliding name admitted under a waiver, another approved
        // and then revoked.
        Registered lookalike = held(lookalike(name));
        waiverService.create(
                lookalike.snapshot().id(),
                NameCollisionGate.RULE_ID,
                WaiverScope.SNAPSHOT,
                null,
                "a sanctioned fork",
                Instant.now().plus(1, ChronoUnit.DAYS),
                "root");
        approve(lookalike.snapshot().id());
        Registered passing = approved(name.toUpperCase(java.util.Locale.ROOT) + "-x");
        snapshotRepository.revoke(passing.snapshot().id(), "revet-policy", "test", Snapshot.REVOKED_BY_REVET);

        revetService.revetSnapshot(subject.snapshot().id(), "alice");
        VettingRepository.Run after =
                vettingRepository.latestRun(subject.snapshot().id()).orElseThrow();

        assertThat(after.runId()).isNotEqualTo(before.runId());
        assertThat(after.chain()).isEqualTo(before.chain());
        assertThat(after.outcome()).isEqualTo(before.outcome());
        assertThat(evidence(after)).isEqualTo(evidence(before));
    }

    /** Each verdict's vetter, state and findings: everything a run says about the content. */
    private static List<String> evidence(VettingRepository.Run run) {
        return run.verdicts().stream()
                .map(verdict -> verdict.vetter() + "|" + verdict.state() + "|" + verdict.findings())
                .toList();
    }
}
