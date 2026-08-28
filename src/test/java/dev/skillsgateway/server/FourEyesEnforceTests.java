package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.FourEyesConflictException;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.storage.GitStorage;
import dev.skillsgateway.server.sync.SyncService;
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
 * Separation of duties under enforcement (GW_0096).
 *
 * <p>Every test here attacks the rule from the side that matters: not "does it let the right
 * person through" but "can the wrong person get content published anyway". A refusal is therefore
 * never asserted on the exception alone — the snapshot must still be undecided, and the facade
 * must still be serving nothing, because a rule that refuses <em>after</em> publishing would look
 * identical from the caller's seat and be worth nothing.
 *
 * <p>Its own Spring context via {@link TestPropertySource}, exactly like the re-vetting mode
 * split: the mode is a deployment decision and must not be settable per call, so the only honest
 * way to exercise both is to run two gateways.
 */
@TestPropertySource(properties = "skills-gateway.approval.four-eyes.mode=enforce")
class FourEyesEnforceTests extends AbstractGatewayTest {

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private GitStorage storage;

    @Autowired
    private FourEyesGate fourEyesGate;

    private static Instant soon() {
        return Instant.now().plus(Duration.ofDays(7));
    }

    /** Nothing decided, nothing served: what a fail-closed refusal has to leave behind. */
    private void assertUntouched(Registered registered) throws Exception {
        Snapshot after = snapshotRepository.findById(registered.snapshot().id()).orElseThrow();
        assertThat(after.state()).isEqualTo(Snapshot.HELD);
        assertThat(after.decidedBy()).isNull();
        assertThat(after.decidedAt()).isNull();
        assertThat(storage.publishedIfServing(registered.marketplace().name())).isEmpty();
        assertThat(gitClone(
                                facadeUrl(registered.marketplace().name(), newPat()),
                                newWorkDir("fourEyesRefused").resolve("repo"))
                        .exitCode())
                .as("nothing was published by the refused approval")
                .isNotZero();
    }

    /** The reviewer who fetched the content cannot be the one who decides it is safe. */
    @Test
    @SVCs({"SVC_GW_0096"})
    void theIngestionActorCannotApproveTheSnapshotTheyIngested() throws Exception {
        assertThat(fourEyesGate.mode()).isEqualTo(SkillsGatewayProperties.FourEyesMode.ENFORCE);
        Registered registered =
                registerAndIngest(uniqueName("fe4ingest"), createUpstream(DEFAULT_MANIFEST), null, "ingrid");
        long id = registered.snapshot().id();

        assertThat(snapshotRepository.findById(id).orElseThrow().ingestedBy())
                .as("the ingestion actor is recorded on the snapshot itself, not only on the ledger")
                .isEqualTo("ingrid");
        assertThatThrownBy(() -> approvalService.approve(id, "ingrid"))
                .isInstanceOf(FourEyesConflictException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(FourEyesConflictException.class))
                .satisfies(refused -> assertThat(refused.conflicts())
                        .containsExactly(
                                new FourEyesConflictException.Conflict(FourEyesGate.ROLE_INGESTED_BY, "ingrid", null)));
        assertUntouched(registered);

        // ...and an independent reviewer is not obstructed by any of it.
        Snapshot approved = approvalService.approve(id, "rachel").snapshot();
        assertThat(approved.state()).isEqualTo(Snapshot.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("rachel");
        assertThat(gitClone(
                                facadeUrl(registered.marketplace().name(), newPat()),
                                newWorkDir("fourEyesAllowed").resolve("repo"))
                        .exitCode())
                .isZero();
    }

    /** Choosing the upstream is a supply-side decision too, however long ago it was made. */
    @Test
    @SVCs({"SVC_GW_0096"})
    void theMarketplaceRegistrantCannotApproveItsSnapshots() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fe4reg"), createUpstream(DEFAULT_MANIFEST), "reggie", null);
        long id = registered.snapshot().id();

        assertThat(marketplaceRepository
                        .findByName(registered.marketplace().name())
                        .orElseThrow()
                        .registeredBy())
                .isEqualTo("reggie");
        assertThatThrownBy(() -> approvalService.approve(id, "reggie"))
                .isInstanceOf(FourEyesConflictException.class)
                .hasMessageContaining(FourEyesGate.ROLE_REGISTERED_BY);
        assertUntouched(registered);

        assertThat(approvalService.approve(id, "rachel").snapshot().state()).isEqualTo(Snapshot.APPROVED);
    }

    /**
     * The bypass, closed. Accepting a finding and then approving past the acceptance is one
     * decision wearing two hats, and it is the route a reviewer refused for ingesting would
     * otherwise take: waive the objection on a fresh copy and approve that instead.
     */
    @Test
    @SVCs({"SVC_GW_0096"})
    void theAuthorOfAWaiverTheApprovalReliesOnCannotApprove() throws Exception {
        Registered registered = registerAndIngest(
                uniqueName("fe4waiver"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)),
                null,
                null);
        long id = registered.snapshot().id();
        var waiver =
                waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "documented dummy key", soon(), "wanda");

        assertThatThrownBy(() -> approvalService.approve(id, "wanda"))
                .isInstanceOf(FourEyesConflictException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(FourEyesConflictException.class))
                .satisfies(refused -> assertThat(refused.conflicts())
                        .containsExactly(new FourEyesConflictException.Conflict(
                                FourEyesGate.ROLE_WAIVER_AUTHOR, "wanda", waiver.id())));
        assertUntouched(registered);

        // Somebody else may rely on Wanda's acceptance: the rule is about the reviewer's own acts,
        // not about waivers being untrustworthy.
        assertThat(approvalService.approve(id, "rachel").snapshot().state()).isEqualTo(Snapshot.APPROVED);
    }

    /**
     * A waiver the approval does not lean on is not an act of supply. The rule reads the waivers
     * that actually suppressed something, so a reviewer's stale, expired or irrelevant waiver
     * lying around the marketplace cannot lock them out of every future approval — which is what
     * comparing against the whole waiver set would do.
     */
    @Test
    @SVCs({"SVC_GW_0096"})
    void aWaiverTheApprovalDoesNotRelyOnIsNotAConflict() throws Exception {
        Registered registered = registerAndIngest(uniqueName("fe4stale"), createUpstream(DEFAULT_MANIFEST), null, null);
        long id = registered.snapshot().id();
        // Clean content: this waiver covers a rule the chain never raised, so nothing is suppressed.
        waiverService.create(id, RULE, WaiverScope.SNAPSHOT, null, "unused acceptance", soon(), "rachel");

        assertThat(approvalService.approve(id, "rachel").fourEyesConflicts()).isEmpty();
        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
    }

    /**
     * The sweep and the webhook are triggers, not judgements. If they conflicted, an estate on
     * automatic sync would have no approvable snapshots at all — the rule would have made the
     * feature it guards unusable.
     */
    @Test
    @SVCs({"SVC_GW_0096"})
    void automatedTriggersAndUnrecordedActorsNeverConflict() throws Exception {
        for (String actor : new String[] {SyncService.SCHEDULER_ACTOR, SyncService.WEBHOOK_ACTOR, null}) {
            Registered registered =
                    registerAndIngest(uniqueName("fe4auto"), createUpstream(DEFAULT_MANIFEST), actor, actor);
            long id = registered.snapshot().id();
            // Approved by the very name the trigger acts under: even that identity is not a person
            // whose independence the rule is protecting.
            String reviewer = actor == null ? "rachel" : actor;
            assertThat(approvalService.approve(id, reviewer).fourEyesConflicts())
                    .as("actor %s never conflicts", actor)
                    .isEmpty();
            assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        }
    }

    /**
     * The same refusal over HTTP, which is the only surface a reviewer actually meets: a 409 whose
     * problem document names the conflicting acts and the setting that imposed them, and a
     * pre-check endpoint that says the same thing before the button is pressed.
     */
    @Test
    @SVCs({"SVC_GW_0096"})
    void theApprovalEndpointRefusesWithAConflictProblemNamingTheRoles() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fe4http"), createUpstream(DEFAULT_MANIFEST), "dana", "dana");
        long id = registered.snapshot().id();

        mockMvc.perform(get("/api/snapshots/{id}/four-eyes", id)
                        .with(oidcLogin().idToken(token -> token.subject("dana"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ENFORCE"))
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.conflicts[*].role")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                FourEyesGate.ROLE_REGISTERED_BY, FourEyesGate.ROLE_INGESTED_BY)));

        mockMvc.perform(post("/api/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("dana")))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Four-eyes rule refused this approval"))
                .andExpect(jsonPath("$.configKey").value(SkillsGatewayProperties.FourEyes.CONFIG_KEY))
                .andExpect(jsonPath("$.conflicts[*].role")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                FourEyesGate.ROLE_REGISTERED_BY, FourEyesGate.ROLE_INGESTED_BY)));
        assertUntouched(registered);

        // The refusal is on the ledger: a control that turns approvals away silently cannot be
        // audited, and "nobody ever tried" must not look like "nobody was ever refused".
        assertThat(fetchLogRepository.list())
                .filteredOn(entry -> registered.marketplace().name().equals(entry.get("marketplace"))
                        && FourEyesGate.EVENT_CONFLICT.equals(entry.get("event")))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.get("principal")).isEqualTo("dana");
                    assertThat(String.valueOf(entry.get("detail")))
                            .contains("mode=ENFORCE")
                            .contains("refused")
                            .contains(FourEyesGate.ROLE_INGESTED_BY)
                            .contains(FourEyesGate.ROLE_REGISTERED_BY);
                });

        // And an independent reviewer sees a clean pre-check and gets through.
        mockMvc.perform(get("/api/snapshots/{id}/four-eyes", id)
                        .with(oidcLogin().idToken(token -> token.subject("rachel"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.conflicts").isEmpty());
        mockMvc.perform(post("/api/snapshots/{id}/approve", id)
                        .with(oidcLogin().idToken(token -> token.subject("rachel")))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /** Rejecting is never gated: refusing content quickly must not need a second pair of eyes. */
    @Test
    @SVCs({"SVC_GW_0096"})
    void rejectionIsNotGatedByTheRule() throws Exception {
        Registered registered =
                registerAndIngest(uniqueName("fe4reject"), createUpstream(DEFAULT_MANIFEST), "dana", "dana");
        long id = registered.snapshot().id();

        assertThat(approvalService.reject(id, "dana").state()).isEqualTo(Snapshot.REJECTED);
    }
}
