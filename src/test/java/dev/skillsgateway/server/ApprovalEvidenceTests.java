package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.FourEyesGate;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.vetting.Waiver;
import dev.skillsgateway.server.vetting.WaiverScope;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.SVCs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * What a person's approval writes, pinned exactly (GW_APPROVAL_0044.10).
 *
 * <p>Automatic approval records its evidence through the same code a person's approval does, and
 * that code is moved out of the controller to make it shareable. These tests are the fixed point
 * of that move: they assert every ledger entry the approval adds, in order, and the one webhook it
 * queues, so a move that drops, reorders or rewords any of them fails here rather than in an
 * auditor's export.
 */
class ApprovalEvidenceTests extends AbstractGatewayTest {

    /** A shaped AWS access key id — belongs to nobody; secret-scan fails on its shape. */
    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    private static final String RULE = "aws-access-key-id";

    private final OidcLoginRequestPostProcessor root = oidcLogin().idToken(token -> token.subject("root"));

    @Autowired
    private WaiverService waiverService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    /** Left registered, a subscriber collects a delivery from every later test's events. */
    private final List<Long> subscribers = new ArrayList<>();

    @AfterEach
    void removeSubscribers() {
        subscribers.forEach(subscriberRepository::delete);
        subscribers.clear();
    }

    private long subscribeToApprovals() {
        long id = webhookService
                .createSubscriber(
                        uniqueName("evidence"), "http://127.0.0.1:1/hook", List.of(WebhookEvent.SNAPSHOT_APPROVED))
                .id();
        subscribers.add(id);
        return id;
    }

    private long lastLedgerId() {
        return fetchLogRepository.list().stream()
                .mapToLong(entry -> ((Number) entry.get("id")).longValue())
                .max()
                .orElse(0);
    }

    private List<Map<String, Object>> entriesSince(long afterId, String marketplace) {
        return fetchLogRepository.list().stream()
                .filter(entry -> ((Number) entry.get("id")).longValue() > afterId)
                .filter(entry -> marketplace.equals(entry.get("marketplace")))
                .toList();
    }

    private void disable(String vetter, String marketplace) throws Exception {
        mockMvc.perform(put("/api/v1/vetting/vetters/{name}/toggle", vetter)
                        .with(root)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"marketplace\": \"%s\", \"reason\": \"evidence pin\"}"
                                .formatted(marketplace)))
                .andExpect(status().isOk());
    }

    /**
     * Every conditional entry at once: a waiver that let the approval through, evidence from a chain
     * the marketplace no longer runs, and a warn-mode self-approval.
     */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.10"})
    void a_persons_approval_writes_its_waiver_decision_staleness_and_conflict_entries_and_one_webhook()
            throws Exception {
        long subscriber = subscribeToApprovals();
        Registered registered = registerAndIngest(
                uniqueName("evidence-full"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)),
                "root",
                "root");
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        Waiver waiver = waiverService.create(
                id,
                RULE,
                WaiverScope.SNAPSHOT,
                sha,
                "documented dummy key",
                Instant.now().plus(Duration.ofDays(7)),
                "root");
        disable("license-scan", name);
        long before = lastLedgerId();

        mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id).with(root).with(csrf()))
                .andExpect(status().isOk());

        assertThat(snapshotRepository.findById(id).orElseThrow().state()).isEqualTo(Snapshot.APPROVED);
        List<Map<String, Object>> written = entriesSince(before, name);
        assertThat(written)
                .extracting(entry -> entry.get("event"))
                .containsExactly(
                        WaiverService.EVENT_APPLIED,
                        "snapshot-approved",
                        ApprovalService.EVENT_APPROVED_ON_SUPERSEDED_CHAIN,
                        FourEyesGate.EVENT_CONFLICT);
        assertThat(written).allSatisfy(entry -> {
            assertThat(entry.get("principal")).isEqualTo("root");
            assertThat(entry.get("sha")).isEqualTo(sha);
        });
        assertThat(String.valueOf(written.get(0).get("detail")))
                .startsWith("waiver=%d; rule=%s; location=".formatted(waiver.id(), RULE))
                .contains("approvedBy=root");
        assertThat(String.valueOf(written.get(1).get("detail"))).startsWith("ingestion-age=");
        assertThat(String.valueOf(written.get(2).get("detail")))
                .startsWith("evidenceChain=")
                .contains("; chainInForce=")
                .contains("license-scan");
        assertThat(String.valueOf(written.get(3).get("detail")))
                .startsWith("mode=WARN, approved, conflicts=")
                .contains(FourEyesGate.ROLE_REGISTERED_BY)
                .contains(FourEyesGate.ROLE_INGESTED_BY);

        assertThat(deliveryRepository.listBySubscriber(subscriber))
                .singleElement()
                .satisfies(delivery -> assertApprovedDelivery(delivery, name, id, sha, "root"));
    }

    /** The override's own entry is written before the decision entry, and nothing else is added. */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.10"})
    void an_administrators_override_writes_its_override_entry_before_the_decision_and_one_webhook() throws Exception {
        long subscriber = subscribeToApprovals();
        Registered registered = registerAndIngest(
                uniqueName("evidence-override"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        String sha = registered.snapshot().sha();
        long before = lastLedgerId();

        mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id)
                        .with(root)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideVetting\": true, \"reason\": \"accepted in TICKET-7\"}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> written = entriesSince(before, name);
        assertThat(written)
                .extracting(entry -> entry.get("event"))
                .containsExactly(ApprovalService.EVENT_OVERRIDE, "snapshot-approved");
        assertThat(String.valueOf(written.get(0).get("detail")))
                .startsWith("reason=accepted in TICKET-7; blockingVetters=")
                .contains("secret-scan")
                .contains("; uncovered=");
        assertThat(String.valueOf(written.get(1).get("detail"))).startsWith("ingestion-age=");

        assertThat(deliveryRepository.listBySubscriber(subscriber))
                .singleElement()
                .satisfies(delivery -> assertApprovedDelivery(delivery, name, id, sha, "root"));
    }

    /** A clean, independent approval adds only the decision entry. */
    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.10"})
    void a_clean_independent_approval_writes_only_the_decision_entry_and_one_webhook() throws Exception {
        long subscriber = subscribeToApprovals();
        Registered registered = registerAndIngest(uniqueName("evidence-clean"), createUpstream(DEFAULT_MANIFEST));
        String name = registered.marketplace().name();
        long id = registered.snapshot().id();
        long before = lastLedgerId();

        mockMvc.perform(post("/api/v1/snapshots/{id}/approve", id).with(root).with(csrf()))
                .andExpect(status().isOk());

        assertThat(entriesSince(before, name))
                .extracting(entry -> entry.get("event"))
                .containsExactly("snapshot-approved");
        assertThat(deliveryRepository.listBySubscriber(subscriber))
                .singleElement()
                .satisfies(delivery -> assertApprovedDelivery(
                        delivery, name, id, registered.snapshot().sha(), "root"));
    }

    private static void assertApprovedDelivery(
            WebhookDelivery delivery, String marketplace, long snapshotId, String sha, String actor) {
        assertThat(delivery.event()).isEqualTo(WebhookEvent.SNAPSHOT_APPROVED);
        assertThat(delivery.payload())
                .contains("\"marketplace\":\"%s\"".formatted(marketplace))
                .contains("\"snapshotId\":%d".formatted(snapshotId))
                .contains("\"sha\":\"%s\"".formatted(sha))
                .contains("\"state\":\"%s\"".formatted(Snapshot.APPROVED))
                .contains("\"actor\":\"%s\"".formatted(actor));
    }
}
