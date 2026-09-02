package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.vetting.Finding;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.RevetVerdict;
import dev.skillsgateway.server.vetting.Severity;
import dev.skillsgateway.server.vetting.VerdictState;
import dev.skillsgateway.server.vetting.VettingChain;
import dev.skillsgateway.server.vetting.VettingRepository;
import dev.skillsgateway.server.vetting.WaiverEvaluation;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verification of auto-quarantine under enforcement (GW_0050, GW_0052, GW_0053, GW_0054).
 *
 * <p>Enforcement is the one thing in the gateway that takes content away from consumers without a
 * person in the loop, so these tests attack it from both directions at once: that it does retract
 * — through a real git client, not a repository field — and that it refuses to retract on evidence
 * that does not name the content.
 *
 * <p>Its own Spring context via {@link TestPropertySource}: the mode is a deployment decision and
 * must not be settable per call, so the only way to exercise both is to run two gateways.
 */
@TestPropertySource(properties = "skills-gateway.vetting.revet.mode=enforce")
@TestPropertySource(
        // Authorization is always enforced (GW_0138), so this suite names the principal it acts as.
        properties = {"skills-gateway.roles.admins=bob"})
class RevetEnforceTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private RevetService revetService;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private VettingRepository vettingRepository;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private GitStorage storage;

    @Autowired
    private SkillsGatewayProperties properties;

    private static Instant soon() {
        return Instant.now().plus(Duration.ofDays(7));
    }

    /** Approved and published although the chain objects — reached the only sanctioned way, then withdrawn. */
    private Registered approvedWithLapsedWaiver(String prefix) throws Exception {
        Registered registered = registerAndIngest(
                uniqueName(prefix),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
        long id = registered.snapshot().id();
        var waiver =
                waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "temporary acceptance", soon(), "alice");
        approve(id);
        waiverService.revoke(waiver.id(), "alice");
        return registered;
    }

    /**
     * The whole retraction, end to end and from outside the process: revoke, stop serving, refuse
     * the ref by name, keep the quarantined copy, and let it back only through a fresh decision.
     *
     * <p>The ref-by-name clause is the one a naive implementation loses. Approval copies both
     * {@code refs/heads/main} and {@code refs/snapshots/<sha>} into the published repository, and
     * upload-pack advertises both — so deleting only the branch would leave the revoked commit
     * fetchable by anyone who knows its SHA, which is everyone who ever cloned it.
     */
    @Test
    @SVCs({"SVC_GW_0050"})
    void enforcedRevettingRevokesTheSnapshotAndStopsTheFacadeServingIt() throws Exception {
        assertThat(revetService.mode()).isEqualTo(SkillsGatewayProperties.RevetMode.ENFORCE);
        Registered registered = approvedWithLapsedWaiver("revetenf");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        String pat = newPat();

        // It really is being served before the re-vet: otherwise "it is not served after" proves nothing.
        Path before = newWorkDir("revetenfbefore");
        assertThat(gitClone(facadeUrl(name, pat), before.resolve("repo")).exitCode())
                .isZero();

        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");

        assertThat(result.classification()).isEqualTo(RevetVerdict.Classification.VIOLATION);
        assertThat(result.revoked()).isTrue();
        Snapshot revoked = snapshotRepository.findById(id).orElseThrow();
        assertThat(revoked.state()).isEqualTo(Snapshot.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.revokedBy()).isEqualTo("alice");
        assertThat(revoked.violation()).contains("secret-scan");
        // The approval record survives the retraction; who approved it is part of the history.
        assertThat(revoked.decidedBy()).isEqualTo("alice");

        // The facade no longer resolves the marketplace at all.
        assertThat(storage.publishedIfServing(name)).isEmpty();
        Path after = newWorkDir("revetenfafter");
        assertThat(gitClone(facadeUrl(name, pat), after.resolve("repo")).exitCode())
                .isNotZero();
        // And the revoked commit cannot be fetched by name either.
        GitResult byRef = git(null, "ls-remote", facadeUrl(name, pat), "refs/snapshots/" + sha);
        assertThat(byRef.output()).doesNotContain(sha);

        // Quarantine is untouched: the content is still there to be re-reviewed.
        try (var quarantine = storage.quarantine(name)) {
            assertThat(quarantine.exactRef("refs/snapshots/" + sha)).isNotNull();
        }

        // A revoked snapshot is not re-publishable while the violation stands...
        assertThatThrownBy(() -> approvalService.approve(id, "bob")).isInstanceOf(VettingBlockedException.class);
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);
        assertThat(storage.publishedIfServing(name)).isEmpty();

        // ...and comes back only through a fresh, gated, recorded approve decision.
        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "bob");
        Snapshot reapproved = approvalService.approve(id, "bob").snapshot();

        assertThat(reapproved.state()).isEqualTo(Snapshot.APPROVED);
        assertThat(reapproved.decidedBy()).isEqualTo("bob");
        assertThat(reapproved.revokedAt()).isNull();
        Path again = newWorkDir("revetenfagain");
        assertThat(gitClone(facadeUrl(name, pat), again.resolve("repo")).exitCode())
                .isZero();
        assertThat(headSha(again.resolve("repo"))).isEqualTo(sha);
    }

    /**
     * The deliberate exception to fail-closed, from both ends.
     *
     * <p>First for real: the chain cannot read the snapshot at all, which is the shape every
     * connector outage takes. Under enforcement — the strictest configuration there is — the
     * snapshot must stay approved and served, because nothing about its content was established.
     *
     * <p>Then exhaustively and purely, over every verdict state the chain can produce, so the rule
     * is verified for the states no built-in connector emits today ({@code PENDING}) and for the
     * degenerate runs a misconfiguration produces (no verdicts, no run).
     */
    @Test
    @SVCs({"SVC_GW_0052"})
    void aConnectorErrorDuringRevettingNeverRevokesTheSnapshot() throws Exception {
        Registered registered = registerAndIngest(uniqueName("revetinconc"), createUpstream(DEFAULT_MANIFEST));
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        approve(id);

        // Take the content away from the scanner without touching the content itself: the pinned
        // commit becomes unreadable, which is exactly what a broken connector looks like to the
        // chain — an ERROR verdict and a blocked run, about a snapshot nothing has examined.
        deleteQuarantine(name);

        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");

        assertThat(result.classification()).isEqualTo(RevetVerdict.Classification.INCONCLUSIVE);
        assertThat(result.revoked()).isFalse();
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(storage.publishedIfServing(name)).isPresent();
        assertThat(gitClone(
                                facadeUrl(name, newPat()),
                                newWorkDir("revetinconcclone").resolve("repo"))
                        .exitCode())
                .isZero();
        // Fail-closed is not weakened: the recorded run still blocks, so nothing can be published
        // off the back of an inconclusive answer.
        assertThat(vettingRepository.run(result.runId()).orElseThrow().outcome())
                .isEqualTo(VettingChain.Outcome.BLOCKED);
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> name.equals(entry.get("marketplace")))
                .anySatisfy(entry -> assertThat(entry.get("event")).isEqualTo(RevetService.EVENT_INCONCLUSIVE));

        // The rule itself, over every state the chain can produce.
        Finding critical = new Finding(RULE, Severity.CRITICAL, "plugins/hello/DEPLOY.md:3", "planted key");
        for (VerdictState state : VerdictState.values()) {
            VettingRepository.Run run = run(state, List.of(critical));
            WaiverEvaluation.Effect blocked = WaiverEvaluation.evaluate(run, List.of(), "abc123", Instant.now());
            RevetVerdict.Classification classification = RevetVerdict.classify(run, blocked);
            if (state.clearing()) {
                assertThat(classification).as("state %s", state).isEqualTo(RevetVerdict.Classification.CLEAR);
            } else if (state == VerdictState.FAIL) {
                assertThat(classification).as("state %s", state).isEqualTo(RevetVerdict.Classification.VIOLATION);
            } else {
                // ERROR and PENDING: the chain did not answer. DISABLED (GW_0143): an administrator
                // switched the connector off, which says nothing about the content. None of the
                // three names a fault, so nothing is retracted.
                assertThat(classification).as("state %s", state).isEqualTo(RevetVerdict.Classification.INCONCLUSIVE);
            }
        }
        // A chain configured to nothing blocks, but names no fault in the content, so it must not
        // become a fleet-wide retraction the first time someone mis-edits the connector list.
        assertThat(RevetVerdict.classify(
                        emptyRun(), WaiverEvaluation.evaluate(emptyRun(), List.of(), "abc123", Instant.now())))
                .isEqualTo(RevetVerdict.Classification.INCONCLUSIVE);
        assertThat(RevetVerdict.classify(null, WaiverEvaluation.noRun()))
                .isEqualTo(RevetVerdict.Classification.INCONCLUSIVE);
    }

    /**
     * The blast radius. Two identities that received a pack must be named; one that only asked for
     * the refs must not — it never got the content, and naming it would bury the ones that did.
     */
    @Test
    @SVCs({"SVC_GW_0053"})
    void aViolationNamesTheIdentitiesThatFetchedTheSnapshot() throws Exception {
        webhookService.createSubscriber(uniqueName("revetsub"), "http://127.0.0.1:1/hook", "*");
        Registered registered = approvedWithLapsedWaiver("revetblast");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();

        String alice = tokenService.create("alice", "blast").token();
        String bob = tokenService.create("bob", "blast").token();
        String carol = tokenService.create("carol", "blast").token();
        assertThat(gitClone(facadeUrl(name, alice), newWorkDir("blastalice").resolve("repo"))
                        .exitCode())
                .isZero();
        assertThat(gitClone(facadeUrl(name, bob), newWorkDir("blastbob").resolve("repo"))
                        .exitCode())
                .isZero();
        // carol only advertises refs; she never receives a pack.
        assertThat(git(null, "ls-remote", facadeUrl(name, carol)).exitCode()).isZero();

        RevetService.RevetResult result = revetService.revetSnapshot(id, "alice");

        assertThat(result.affected())
                .extracting(FetchLogRepository.Fetcher::principal)
                .containsExactlyInAnyOrder("alice", "bob");
        assertThat(result.affected()).allSatisfy(fetcher -> {
            assertThat(fetcher.fetches()).isPositive();
            assertThat(fetcher.lastFetch()).isNotNull();
        });

        List<String> events = webhookService.listDeliveries(200).stream()
                .map(WebhookDelivery::event)
                .toList();
        assertThat(events).contains(WebhookEvent.SNAPSHOT_REVET_VIOLATION, WebhookEvent.SNAPSHOT_REVOKED);
    }

    /** Every step of a retraction and its reversal, readable from the ledger alone. */
    @Test
    @SVCs({"SVC_GW_0054"})
    void theLedgerRecordsTheRetroactiveViolationAndEveryTransition() throws Exception {
        Registered registered = approvedWithLapsedWaiver("revetledger");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        assertThat(gitClone(
                                facadeUrl(
                                        name, tokenService.create("dave", "led").token()),
                                newWorkDir("ledgerdave").resolve("repo"))
                        .exitCode())
                .isZero();

        revetService.revetSnapshot(id, "alice");
        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "bob");
        // Through the endpoint, not the service: the fresh approve decision has to be the ordinary
        // one a reviewer makes, ledger entry and all, or "re-publishable only by a recorded
        // decision" would be a claim about a code path nobody uses.
        mockMvc.perform(post("/api/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("bob")))
                        .with(csrf()))
                .andExpect(status().isOk());

        List<Map<String, Object>> entries = fetchLogRepository.list().stream()
                .filter(entry -> name.equals(entry.get("marketplace")))
                .toList();

        assertThat(entries)
                .filteredOn(entry -> "vetting-completed".equals(entry.get("event")))
                .anySatisfy(entry -> assertThat(String.valueOf(entry.get("detail")))
                        .contains("trigger=" + VettingRepository.TRIGGER_REVET_MANUAL)
                        .contains("chain="));
        assertThat(entries)
                .filteredOn(entry -> RevetService.EVENT_VIOLATION.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("sha")).isEqualTo(sha);
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("mode=ENFORCE")
                            .contains("secret-scan")
                            .contains(RULE);
                });
        assertThat(entries)
                .filteredOn(entry -> "revet-violation-affected".equals(entry.get("event")))
                .anySatisfy(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("principal=dave"));
        assertThat(entries)
                .filteredOn(entry -> RevetService.EVENT_REVOKED.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> assertThat(entry.get("principal")).isEqualTo("alice"));
        assertThat(entries)
                .filteredOn(entry -> RevetService.EVENT_UNPUBLISHED.equals(entry.get("event")))
                .singleElement()
                .satisfies(
                        entry -> assertThat(String.valueOf(entry.get("detail"))).contains("serves nothing"));
        assertThat(entries)
                .filteredOn(entry -> "snapshot-approved".equals(entry.get("event")))
                .isNotEmpty();
    }

    /**
     * Retention's approved guard was categorical, and {@code revoked} is not approved — so the
     * interaction had to be decided rather than inherited. It is: a revoked snapshot is treated as
     * a rejected one, deletable by an administrator and by the superseded criterion, and never by
     * {@code held-too-long}, which names {@code held} itself.
     */
    @Test
    void retentionTreatsARevokedSnapshotLikeARejectedOne() throws Exception {
        Registered registered = approvedWithLapsedWaiver("revetreten");
        long id = registered.snapshot().id();
        revetService.revetSnapshot(id, "alice");
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);

        // held-max-age is 1ms in the test fixture, so a 'held' snapshot of this marketplace would
        // be selected immediately. The revoked one is not, because that criterion names 'held'.
        assertThat(retentionCandidates(registered.marketplace().name()))
                .as("a revoked snapshot is never selected by held-too-long")
                .doesNotContain(id);

        // But an administrator can delete it: it is not being served, so nothing is lost that
        // anyone could fetch. The approved guard would have refused this before the revocation.
        assertThat(snapshotRepository.softDelete(id, "manual", Instant.now().plus(Duration.ofHours(1))))
                .isPresent();
        assertThat(snapshotRepository.findById(id).orElseThrow().deleted()).isTrue();
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.REVOKED);
    }

    private List<Long> retentionCandidates(String marketplace) {
        long marketplaceId =
                marketplaceRepository.findByName(marketplace).orElseThrow().id();
        return snapshotRepository
                .candidates(marketplaceId, marketplace, true, Instant.now(), false, Instant.now(), Instant.EPOCH, 50)
                .stream()
                .map(candidate -> candidate.snapshot().id())
                .toList();
    }

    /** Removes a marketplace's quarantine repository, so the chain can no longer read its content. */
    private void deleteQuarantine(String marketplace) throws IOException {
        Path repository = properties.dataDir().resolve("quarantine").resolve(marketplace + ".git");
        try (Stream<Path> paths = Files.walk(repository)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private static VettingRepository.Run run(VerdictState state, List<Finding> findings) {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_REVET_SCHEDULED,
                VettingChain.aggregate(List.of(state)),
                Instant.now(),
                Instant.now(),
                "secret-scan@1",
                List.of(new VettingRepository.VerdictView(1L, "secret-scan", 0, state, null, null, findings)));
    }

    private static VettingRepository.Run emptyRun() {
        return new VettingRepository.Run(
                1L,
                1L,
                VettingRepository.TRIGGER_REVET_SCHEDULED,
                VettingChain.Outcome.BLOCKED,
                Instant.now(),
                Instant.now(),
                "",
                List.of());
    }
}
