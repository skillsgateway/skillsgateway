package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.mirror.ForgeMirrorService;
import dev.skillsgateway.server.mirror.MirrorReport;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import io.github.reqstool.annotations.SVCs;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The mirror failing, which is the case the whole design is arranged around (GW_0170, GW_0171).
 *
 * <p>Its own context, pointed at a path that is not a repository, because "the mirror is broken" is
 * a property of a deployment rather than of a call: there is no way to break the mirror from inside
 * a test that shares a working one, and a fake would prove nothing about the real transport. Every
 * push and every ls-remote here therefore fails the way an unreachable forge fails.
 *
 * <p>What is asserted is entirely about things <em>other</em> than the mirror: the approval, the
 * revocation and the bytes the facade serves. If any of them moved when the mirror broke, the
 * mirror would be an enforcement path.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.mirror.enabled=true",
            "skills-gateway.mirror.marketplace=" + ForgeMirrorFailureTests.MIRRORED,
            "skills-gateway.mirror.url=file:///nonexistent/skills-gateway-forge-mirror-target.git",
            "skills-gateway.mirror.max-attempts=2",
            "skills-gateway.mirror.retry-delay=10ms",
            "skills-gateway.mirror.timeout=2s",
            "skills-gateway.vetting.revet.mode=enforce",
            "skills-gateway.roles.admins=alice"
        })
class ForgeMirrorFailureTests extends AbstractGatewayTest {

    static final String MIRRORED = "unmirrorable";

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private ForgeMirrorService mirror;

    @Autowired
    private GitStorage storage;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private RevetService revetService;

    @Autowired
    private FetchLogRepository ledger;

    @Test
    @SVCs({"SVC_GW_0170", "SVC_GW_0171", "SVC_GW_0172"})
    void a_mirror_that_cannot_be_pushed_to_changes_neither_the_decisions_nor_what_is_served() throws Exception {
        assertThat(mirror.enabled()).isTrue();
        Registered registered = registerAndIngest(
                MIRRORED, createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        String pat = newPat();
        var waiver = waiverService.create(
                id,
                RULE,
                WaiverScope.SNAPSHOT,
                null,
                "temporary acceptance",
                Instant.now().plus(Duration.ofDays(7)),
                "alice");

        // The approval itself: it returns, it returns the approved snapshot, and nothing about the
        // mirror reaches its caller.
        Snapshot approved = approve(id);
        assertThat(approved.state()).isEqualTo(Snapshot.APPROVED);

        // And a real client gets the approved commit through the facade, from outside the process.
        Path clone = newWorkDir("mirrorfail");
        assertThat(gitClone(facadeUrl(MIRRORED, pat), clone.resolve("repo")).exitCode())
                .isZero();
        assertThat(headSha(clone.resolve("repo"))).isEqualTo(sha);

        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();
        MirrorReport failed = mirror.report();
        assertThat(failed.enabled()).isTrue();
        assertThat(failed.reachable()).isFalse();
        // Fail-closed: an unreachable mirror is never reported as agreeing with what is served.
        assertThat(failed.inSync()).isFalse();
        assertThat(failed.servedTip()).isEqualTo(sha);
        assertThat(failed.lastAttemptOutcome()).isEqualTo(MirrorReport.FAILED);
        assertThat(failed.error()).isNotBlank();
        assertThat(ledgerEvents()).contains(ForgeMirrorService.EVENT_FAILED);

        // Revocation, with the mirror still broken: the gateway's own state is authoritative, so
        // the retraction happens in full and the mirror's failure to follow is drift, not a veto.
        waiverService.revoke(waiver.id(), "alice");
        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");
        assertThat(result.revoked()).isTrue();
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);
        assertThat(storage.publishedIfServing(MIRRORED)).isEmpty();
        Path after = newWorkDir("mirrorfailafter");
        assertThat(gitClone(facadeUrl(MIRRORED, pat), after.resolve("repo")).exitCode())
                .isNotZero();

        assertThat(mirror.awaitQuiescence(Duration.ofSeconds(30))).isTrue();
        assertThat(mirror.report().lastAttemptOutcome()).isEqualTo(MirrorReport.FAILED);
    }

    private List<String> ledgerEvents() {
        return ledger.list().stream()
                .filter(entry -> MIRRORED.equals(entry.get("marketplace")))
                .map(entry -> String.valueOf(entry.get("event")))
                .toList();
    }
}
