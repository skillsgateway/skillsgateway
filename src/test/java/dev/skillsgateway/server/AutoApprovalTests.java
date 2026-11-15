package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalPostureService;
import dev.skillsgateway.server.approval.ApprovalPostureService.Posture;
import dev.skillsgateway.server.approval.ApprovalPostureService.PostureRequest;
import dev.skillsgateway.server.approval.AutoApprovalService;
import dev.skillsgateway.server.approval.RevocationService;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.storage.ServedTip;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.RevetVerdict;
import dev.skillsgateway.server.vetting.Waiver;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Automatic approval end to end, in a gateway with it on (GW_APPROVAL_0044): an internal
 * marketplace on the in-process forge, ingested over HTTP, approved and served with no person.
 *
 * <p>Its own context, the one the change adds: the shared context keeps the mode off and the
 * re-vetting sweep disabled. The sweep interval is long, so no background tick runs here; the
 * triggers under test are the direct ones.
 */
@TestPropertySource(
        properties = {
            "skills-gateway.approval.auto.mode=on",
            "skills-gateway.vetting.revet.enabled=true",
            "skills-gateway.vetting.revet.interval=3650d"
        })
@SVCs({"SVC_GW_APPROVAL_0044"})
class AutoApprovalTests extends AbstractExternalSourceTest {

    @DynamicPropertySource
    static void internalPrefix(DynamicPropertyRegistry registry) {
        registry.add("skills-gateway.approval.auto.internal-prefixes", () -> FORGE.baseUrl() + "/acme");
    }

    private static final String SECRET_PATH = "plugins/hello/DEPLOY.md";

    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    @Autowired
    private ApprovalPostureService postureService;

    @Autowired
    private RevetService revetService;

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private ServedTip servedTip;

    @Autowired
    private AutoApprovalService autoApprovalService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    private Marketplace internalMarketplace(String repo) throws Exception {
        FORGE.publish(
                repo, Map.of(MANIFEST_PATH, DEFAULT_MANIFEST, "plugins/hello/skills/hello/SKILL.md", CONFORMANT_SKILL));
        Marketplace marketplace = marketplaceRepository.register(
                uniqueName("auto"),
                FORGE.baseUrl() + "/" + repo + ".git",
                null,
                Marketplace.ORIGIN_UPSTREAM,
                Marketplace.PUSH_APPEND_ONLY,
                null);
        postureService.set(
                marketplace.name(),
                new PostureRequest(
                        Posture.AUTO, "our own skills repository", Instant.now().plus(Duration.ofDays(30)), null),
                "root");
        return marketplace;
    }

    private List<String> ledger(Marketplace marketplace, String event) {
        return jdbc.sql("SELECT actor_type::text || ' ' || coalesce(detail, '') FROM fetch_log"
                        + " WHERE marketplace = :marketplace AND event = :event ORDER BY id")
                .param("marketplace", marketplace.name())
                .param("event", event)
                .query(String.class)
                .list();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044", "SVC_GW_APPROVAL_0044.8", "SVC_GW_APPROVAL_0044.11"})
    void anInternalMarketplaceServesAMarkdownOnlyUpdateWithNoPerson() throws Exception {
        String repo = "acme/" + uniqueName("skills");
        Marketplace marketplace = internalMarketplace(repo);

        Snapshot first = ingestionService.ingest(marketplace, null);
        Snapshot firstNow = snapshotRepository.findById(first.id()).orElseThrow();
        assertThat(firstNow.state()).as(skipReason(first)).isEqualTo(Snapshot.APPROVED);
        assertThat(firstNow.decidedBy()).isEqualTo(AutoApprovalService.ACTOR);

        FORGE.publish(
                repo, Map.of("plugins/hello/skills/hello/SKILL.md", CONFORMANT_SKILL + "\nNow with more words.\n"));
        Snapshot update = ingestionService.ingest(marketplace, null);

        Snapshot updateNow = snapshotRepository.findById(update.id()).orElseThrow();
        assertThat(updateNow.state()).as(skipReason(update)).isEqualTo(Snapshot.APPROVED);
        assertThat(updateNow.decidedBy()).isEqualTo(AutoApprovalService.ACTOR);
        assertThat(servedTip.of(marketplace.name())).contains(update.sha());
        assertThat(ledger(marketplace, AutoApprovalService.EVENT_APPROVED))
                .hasSize(2)
                .last()
                .satisfies(entry -> assertThat(entry)
                        .startsWith("system ")
                        .contains("basis=internal-prefix " + FORGE.baseUrl() + "/acme")
                        .contains("grantor=root")
                        .contains("baseline=" + first.sha()));
        assertThat(ledger(marketplace, "snapshot-approved")).hasSize(2);
    }

    /** The decision entry and the one webhook a person's clean approval writes (ApprovalEvidenceTests). */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.10"})
    void anAutomaticApprovalWritesTheDecisionEntryAndOneWebhookAsAPersonsDoes() throws Exception {
        long subscriber = webhookService
                .createSubscriber(
                        uniqueName("auto-evidence"), "http://127.0.0.1:1/hook", List.of(WebhookEvent.SNAPSHOT_APPROVED))
                .id();
        try {
            Marketplace marketplace = internalMarketplace("acme/" + uniqueName("skills"));
            Snapshot approved = ingestionService.ingest(marketplace, null);
            assertThat(snapshotRepository.findById(approved.id()).orElseThrow().state())
                    .as(skipReason(approved))
                    .isEqualTo(Snapshot.APPROVED);

            assertThat(ledger(marketplace, "snapshot-approved"))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry).startsWith("system ingestion-age="));
            assertThat(deliveryRepository.listBySubscriber(subscriber))
                    .singleElement()
                    .satisfies(delivery -> {
                        assertThat(delivery.event()).isEqualTo(WebhookEvent.SNAPSHOT_APPROVED);
                        assertThat(delivery.payload())
                                .contains("\"marketplace\":\"%s\"".formatted(marketplace.name()))
                                .contains("\"snapshotId\":%d".formatted(approved.id()))
                                .contains("\"sha\":\"%s\"".formatted(approved.sha()))
                                .contains("\"actor\":\"%s\"".formatted(AutoApprovalService.ACTOR));
                    });
        } finally {
            subscriberRepository.delete(subscriber);
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044", "SVC_GW_APPROVAL_0044.6", "SVC_GW_APPROVAL_0044.11", "SVC_GW_APPROVAL_0044.12"})
    void theSameUpdateWithAnExternalSourceAddedStaysHeld() throws Exception {
        String repo = "acme/" + uniqueName("skills");
        Marketplace marketplace = internalMarketplace(repo);
        Snapshot first = ingestionService.ingest(marketplace, null);
        assertThat(snapshotRepository.findById(first.id()).orElseThrow().state())
                .as(skipReason(first))
                .isEqualTo(Snapshot.APPROVED);

        String tools = "acme/" + uniqueName("tools");
        FORGE.publish(tools, Map.of("skills/tool/SKILL.md", "# Tool\n"));
        FORGE.publish(
                repo,
                Map.of(
                        MANIFEST_PATH,
                        manifestWithExternal(tools),
                        "plugins/hello/skills/hello/SKILL.md",
                        CONFORMANT_SKILL + "\nNow with more words.\n"));
        Snapshot update = ingestionService.ingest(marketplace, null);

        Snapshot updateNow = snapshotRepository.findById(update.id()).orElseThrow();
        assertThat(updateNow.state()).isEqualTo(Snapshot.HELD);
        assertThat(skipReason(update)).startsWith("external-source");
        assertThat(servedTip.of(marketplace.name())).contains(first.sha());
        assertThat(ledger(marketplace, AutoApprovalService.EVENT_SKIPPED))
                .singleElement()
                .satisfies(entry -> assertThat(entry).startsWith("system external-source"));

        autoApprovalService.attempt(update.id());
        assertThat(ledger(marketplace, AutoApprovalService.EVENT_SKIPPED))
                .as("the same reason again is not ledgered again")
                .hasSize(1);

        mockMvc.perform(get("/api/v1/snapshots/{id}/auto-approval", update.id())
                        .with(oidcLogin().idToken(token -> token.subject("root"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("on"))
                .andExpect(jsonPath("$.basis").value("internal-prefix"))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.refusal").value(org.hamcrest.Matchers.startsWith("external-source")));
    }

    /**
     * Content no person looked at is recalled by re-vetting whatever the mode; content a person
     * vouched for follows the mode, here warn. Both carry the same secret, waived by path and then
     * the waiver revoked, so the two differ only in who approved them.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.13"})
    void underWarnAViolationRevokesAnAutomaticApprovalAndOnlyRecordsAPersons() throws Exception {
        assertThat(revetService.mode()).isEqualTo(SkillsGatewayProperties.RevetMode.WARN);
        String repo = "acme/" + uniqueName("skills");
        Marketplace auto = internalMarketplace(repo);
        FORGE.publish(repo, Map.of(SECRET_PATH, PLANTED_SECRET));
        Snapshot baseline = ingestionService.ingest(auto, null);
        Waiver autoWaiver = waivePath(baseline);
        approvalService.approve(baseline.id(), "root");
        FORGE.publish(
                repo, Map.of("plugins/hello/skills/hello/SKILL.md", CONFORMANT_SKILL + "\nNow with more words.\n"));
        Snapshot unattended = ingestionService.ingest(auto, null);
        assertThat(snapshotRepository.findById(unattended.id()).orElseThrow().decidedBy())
                .as(skipReason(unattended))
                .isEqualTo(AutoApprovalService.ACTOR);

        Registered person = registerAndIngest(
                uniqueName("person"), createUpstream(DEFAULT_MANIFEST, Map.of(SECRET_PATH, PLANTED_SECRET)));
        Waiver personWaiver = waivePath(person.snapshot());
        approvalService.approve(person.snapshot().id(), "root");

        waiverService.revoke(autoWaiver.id(), "root");
        waiverService.revoke(personWaiver.id(), "root");
        RevetService.RevetResult recalled = revetService.revetSnapshot(unattended.id(), "root");
        RevetService.RevetResult recorded =
                revetService.revetSnapshot(person.snapshot().id(), "root");

        assertThat(recalled.classification()).isEqualTo(RevetVerdict.Classification.VIOLATION);
        assertThat(recalled.revoked()).isTrue();
        assertThat(snapshotRepository.findById(unattended.id()).orElseThrow().state())
                .isEqualTo(Snapshot.REVOKED);
        assertThat(servedTip.of(auto.name()))
                .as("re-vetting's quarantine serves nothing")
                .isEmpty();
        assertThat(ledger(auto, RevetService.EVENT_VIOLATION))
                .singleElement()
                .satisfies(entry -> assertThat(entry).contains("mode=ENFORCE"));
        assertThat(ledger(auto, RevetService.EVENT_REVOKED)).hasSize(1);

        assertThat(recorded.classification()).isEqualTo(RevetVerdict.Classification.VIOLATION);
        assertThat(recorded.revoked()).isFalse();
        assertThat(snapshotRepository
                        .findById(person.snapshot().id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.APPROVED);
        assertThat(servedTip.of(person.marketplace().name()))
                .contains(person.snapshot().sha());
        assertThat(ledger(person.marketplace(), RevetService.EVENT_VIOLATION))
                .singleElement()
                .satisfies(entry -> assertThat(entry).contains("mode=WARN"));
        assertThat(ledger(person.marketplace(), RevetService.EVENT_REVOKED)).isEmpty();
    }

    /**
     * Two internal marketplaces, each with an automatic approval before {@code since} and after it,
     * a third with an automatic approval before it and two after, and a person's approval after it.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.14"})
    void bulkWithdrawalRevokesExactlyTheAutomaticApprovalsSinceATimeNewestFirst() throws Exception {
        Marketplace first = internalMarketplace("acme/" + uniqueName("skills"));
        Marketplace second = internalMarketplace("acme/" + uniqueName("skills"));
        Marketplace third = internalMarketplace("acme/" + uniqueName("skills"));
        Snapshot firstBefore = autoApproved(first, 0);
        Snapshot secondBefore = autoApproved(second, 0);
        Snapshot thirdBefore = autoApproved(third, 0);
        Instant since = Instant.now();
        Thread.sleep(5);
        Snapshot firstAfter = autoApproved(first, 1);
        Snapshot thirdMiddle = autoApproved(third, 1);
        Snapshot secondAfter = autoApproved(second, 1);
        Snapshot thirdAfter = autoApproved(third, 2);
        Registered person = registerAndIngest(uniqueName("person"), createUpstream(DEFAULT_MANIFEST));
        approvalService.approve(person.snapshot().id(), "root");

        String request = withdrawal(since, "previous_approved");
        mockMvc.perform(post("/api/v1/approval/auto/withdraw")
                        .with(oidcLogin().idToken(token -> token.subject("carol")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
        assertThat(snapshotRepository.findById(thirdAfter.id()).orElseThrow().state())
                .isEqualTo(Snapshot.APPROVED);

        mockMvc.perform(post("/api/v1/approval/auto/withdraw")
                        .with(oidcLogin().idToken(token -> token.subject("root")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn[*].snapshot.id")
                        .value(org.hamcrest.Matchers.contains(
                                (int) thirdAfter.id(), (int) secondAfter.id(), (int) thirdMiddle.id(), (int)
                                        firstAfter.id())));

        for (Snapshot withdrawn : List.of(firstAfter, secondAfter, thirdMiddle, thirdAfter)) {
            assertThat(snapshotRepository.findById(withdrawn.id()).orElseThrow().state())
                    .isEqualTo(Snapshot.REVOKED);
        }
        for (Snapshot kept : List.of(firstBefore, secondBefore, thirdBefore, person.snapshot())) {
            assertThat(snapshotRepository.findById(kept.id()).orElseThrow().state())
                    .isEqualTo(Snapshot.APPROVED);
        }
        assertThat(servedTip.of(first.name())).contains(firstBefore.sha());
        assertThat(servedTip.of(second.name())).contains(secondBefore.sha());
        assertThat(servedTip.of(third.name())).contains(thirdBefore.sha());
        assertThat(servedTip.of(person.marketplace().name()))
                .contains(person.snapshot().sha());
        assertThat(ledger(third, RevocationService.EVENT_ROLLED_BACK))
                .as("the third returns to the snapshot before since, never to the one in the middle")
                .singleElement()
                .satisfies(entry -> assertThat(entry).endsWith(thirdAfter.sha()));
        assertThat(ledger(third, RevocationService.EVENT_REVOKED)).hasSize(2);
    }

    /** A request that cannot be honoured whole changes nothing: no reason, no choice, nothing to return to. */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.14"})
    void bulkWithdrawalThatCannotBeHonouredWithdrawsNothing() throws Exception {
        Marketplace returnable = internalMarketplace("acme/" + uniqueName("skills"));
        Snapshot returnableBefore = autoApproved(returnable, 0);
        Instant since = Instant.now();
        Thread.sleep(5);
        Marketplace alone = internalMarketplace("acme/" + uniqueName("skills"));
        Snapshot only = autoApproved(alone, 0);
        Snapshot returnableAfter = autoApproved(returnable, 1);

        withdraw("{\"since\": \"%s\", \"serveAfter\": \"nothing\"}".formatted(since))
                .andExpect(status().isUnprocessableEntity());
        withdraw("{\"since\": \"%s\", \"reason\": \"bad push\"}".formatted(since))
                .andExpect(status().isUnprocessableEntity());
        withdraw("{\"reason\": \"bad push\", \"serveAfter\": \"nothing\"}").andExpect(status().isUnprocessableEntity());
        withdraw(withdrawal(since, "previous_approved"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.marketplace").value(alone.name()));
        assertThat(snapshotRepository.findById(only.id()).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        assertThat(servedTip.of(alone.name())).contains(only.sha());
        assertThat(snapshotRepository
                        .findById(returnableAfter.id())
                        .orElseThrow()
                        .state())
                .as("the newer withdrawal, which could have been honoured, is not made either")
                .isEqualTo(Snapshot.APPROVED);
        assertThat(servedTip.of(returnable.name())).contains(returnableAfter.sha());

        withdraw(withdrawal(since, "nothing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn[*].snapshot.id")
                        .value(org.hamcrest.Matchers.contains((int) returnableAfter.id(), (int) only.id())))
                .andExpect(jsonPath("$.withdrawn[1].nowServing").doesNotExist());
        assertThat(servedTip.of(alone.name())).isEmpty();
        assertThat(snapshotRepository
                        .findById(returnableBefore.id())
                        .orElseThrow()
                        .state())
                .isEqualTo(Snapshot.APPROVED);
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/approval/auto/withdraw")
                .with(oidcLogin().idToken(token -> token.subject("root")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String withdrawal(Instant since, String serveAfter) {
        return "{\"since\": \"%s\", \"reason\": \"a compromised publishing account\", \"serveAfter\": \"%s\"}"
                .formatted(since, serveAfter);
    }

    /** Publishes a Markdown-only revision and returns its snapshot, approved with no person. */
    private Snapshot autoApproved(Marketplace marketplace, int revision) throws Exception {
        String repo = marketplace.url().substring(FORGE.baseUrl().length() + 1).replaceFirst("\\.git$", "");
        if (revision > 0) {
            FORGE.publish(
                    repo,
                    Map.of("plugins/hello/skills/hello/SKILL.md", CONFORMANT_SKILL + "\nRevision " + revision + ".\n"));
        }
        Snapshot snapshot = ingestionService.ingest(marketplace, null);
        assertThat(snapshotRepository.findById(snapshot.id()).orElseThrow().decidedBy())
                .as(skipReason(snapshot))
                .isEqualTo(AutoApprovalService.ACTOR);
        return snapshot;
    }

    private Waiver waivePath(Snapshot snapshot) {
        return waiverService.create(
                snapshot.id(),
                RULE,
                WaiverScope.PATH,
                SECRET_PATH,
                "documented dummy key",
                Instant.now().plus(Duration.ofDays(7)),
                "root");
    }

    private String skipReason(Snapshot snapshot) {
        return jdbc.sql("SELECT auto_approval_skip_reason FROM snapshots WHERE id = :id")
                .param("id", snapshot.id())
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
