package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalPostureService;
import dev.skillsgateway.server.approval.ApprovalPostureService.Posture;
import dev.skillsgateway.server.approval.ApprovalPostureService.PostureRequest;
import dev.skillsgateway.server.approval.AutoApprovalService;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.storage.ServedTip;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private ApprovalPostureService postureService;

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

    private String skipReason(Snapshot snapshot) {
        return jdbc.sql("SELECT auto_approval_skip_reason FROM snapshots WHERE id = :id")
                .param("id", snapshot.id())
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
