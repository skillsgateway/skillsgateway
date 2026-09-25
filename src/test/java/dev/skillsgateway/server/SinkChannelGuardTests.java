package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.audit.AuditExportService;
import dev.skillsgateway.server.config.SkillsGatewayProperties.DeclaredWebhook;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Estate;
import dev.skillsgateway.server.estate.EstateReconciler;
import dev.skillsgateway.server.estate.EstateReconciliation;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.AuditSinkRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import io.github.reqstool.annotations.SVCs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An audit sink delivers through an ordinary webhook subscriber, and nothing but the sink may remove
 * or rewrite that channel (GW_WEBHOOK_0011): not the webhooks API, not the declarative estate, and not
 * a raw delete in storage.
 */
class SinkChannelGuardTests extends AbstractGatewayTest {

    @Autowired
    private AuditExportService exportService;

    @Autowired
    private AuditSinkRepository sinkRepository;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private EstateReconciler reconciler;

    @Autowired
    private JdbcClient jdbc;

    private final List<Long> sinks = new ArrayList<>();
    private final List<Long> subscribers = new ArrayList<>();

    /**
     * A subscriber left registered collects a delivery per event the rest of the suite emits and
     * crowds other classes' dispatch batches (see WebhookTests), so everything made here is removed.
     */
    @AfterEach
    void removeWhatThisTestRegistered() {
        sinks.forEach(exportService::deleteSink);
        subscribers.forEach(subscriberRepository::delete);
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void the_webhooks_api_refuses_to_delete_a_sinks_channel_and_changes_nothing() throws Exception {
        String name = uniqueName("siem");
        long sinkId = createSink(name);
        AuditSink before = sinkRepository.findById(sinkId).orElseThrow();
        long deletions = ledgerCount("webhook-subscriber-deleted");

        String problem = mockMvc.perform(delete("/api/v1/webhooks/%d".formatted(before.subscriberId()))
                        .with(oidcLogin()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat((String) JsonPath.read(problem, "$.detail"))
                .contains(name)
                .contains("/api/v1/audit/sinks/%d".formatted(sinkId));
        AuditSink after = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(after.cursorPosition()).isEqualTo(before.cursorPosition());
        assertThat(subscriberRepository.findById(before.subscriberId())).isPresent();
        assertThat(ledgerCount("webhook-subscriber-deleted")).isEqualTo(deletions);
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void a_lifecycle_subscriber_is_still_deleted() throws Exception {
        long id = createSubscriber(uniqueName("hook"));

        mockMvc.perform(delete("/api/v1/webhooks/%d".formatted(id)).with(oidcLogin()))
                .andExpect(status().isNoContent());

        assertThat(subscriberRepository.findById(id)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void a_declared_webhook_named_like_a_sink_fails_its_entry_and_leaves_the_channel_alone() throws Exception {
        String name = uniqueName("siem");
        long sinkId = createSink(name);
        WebhookSubscriber channel = subscriberRepository
                .findById(sinkRepository.findById(sinkId).orElseThrow().subscriberId())
                .orElseThrow();
        String sibling = uniqueName("hook");
        long updates = ledgerCount("webhook-subscriber-updated");

        EstateReconciliation report = reconciler.reconcile(
                new Estate(
                        null,
                        null,
                        List.of(
                                new DeclaredWebhook(
                                        name,
                                        "https://elsewhere.invalid/hook",
                                        List.of("marketplace.snapshot.approved"),
                                        "declared-secret-0123456789abcdef"),
                                new DeclaredWebhook(
                                        sibling,
                                        "https://sibling.invalid/hook",
                                        null,
                                        "sibling-secret-0123456789abcdef")),
                        null,
                        null),
                "api");

        subscriberRepository.findByName(sibling).ifPresent(created -> subscribers.add(created.id()));
        EstateReconciliation.Entry refused = entry(report, name);
        assertThat(refused.action()).isEqualTo("failed");
        assertThat(refused.detail()).contains(name).contains("audit sink");
        WebhookSubscriber unchanged =
                subscriberRepository.findById(channel.id()).orElseThrow();
        assertThat(unchanged.url()).isEqualTo(channel.url());
        assertThat(unchanged.secret()).isEqualTo(channel.secret());
        assertThat(unchanged.events()).isEqualTo(channel.events());
        assertThat(entry(report, sibling).action()).isEqualTo("created");
        // The sibling's creation is not an update, so the count of updates is unchanged by this run.
        assertThat(ledgerCount("webhook-subscriber-updated")).isEqualTo(updates);
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void deleting_a_sink_removes_the_sink_and_its_channel() throws Exception {
        long sinkId = createSink(uniqueName("siem"));
        long channelId = sinkRepository.findById(sinkId).orElseThrow().subscriberId();

        mockMvc.perform(delete("/api/v1/audit/sinks/%d".formatted(sinkId)).with(oidcLogin()))
                .andExpect(status().isNoContent());

        assertThat(sinkRepository.findById(sinkId)).isEmpty();
        assertThat(subscriberRepository.findById(channelId)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void removing_a_sink_is_all_or_nothing() throws Exception {
        long sinkId = createSink(uniqueName("siem"));
        long channelId = sinkRepository.findById(sinkId).orElseThrow().subscriberId();
        // A real failure of the channel's removal, raised by the database after the sink row went,
        // rather than a mocked repository: what is under test is the transaction boundary.
        jdbc.sql("""
                        CREATE OR REPLACE FUNCTION refuse_channel_delete() RETURNS trigger AS $$
                        BEGIN RAISE EXCEPTION 'channel removal refused for the test'; END $$ LANGUAGE plpgsql
                        """).update();
        jdbc.sql("""
                        CREATE TRIGGER refuse_channel_delete BEFORE DELETE ON webhook_subscribers
                        FOR EACH ROW WHEN (OLD.id = %d) EXECUTE FUNCTION refuse_channel_delete()
                        """.formatted(channelId)).update();
        try {
            assertThatThrownBy(() -> exportService.deleteSink(sinkId)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.sql("DROP TRIGGER refuse_channel_delete ON webhook_subscribers")
                    .update();
        }

        assertThat(sinkRepository.findById(sinkId)).as("the sink row came back").isPresent();
        assertThat(subscriberRepository.findById(channelId)).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void storage_refuses_a_raw_delete_of_a_sinks_channel() throws Exception {
        long sinkId = createSink(uniqueName("siem"));
        long channelId = sinkRepository.findById(sinkId).orElseThrow().subscriberId();

        assertThatThrownBy(() -> subscriberRepository.delete(channelId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(sinkRepository.findById(sinkId)).isPresent();
        assertThat(subscriberRepository.findById(channelId)).isPresent();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0011"})
    void the_listing_marks_a_sinks_channel_with_its_sink() throws Exception {
        String sinkName = uniqueName("siem");
        long sinkId = createSink(sinkName);
        long channelId = sinkRepository.findById(sinkId).orElseThrow().subscriberId();
        long lifecycleId = createSubscriber(uniqueName("hook"));

        String listed = mockMvc.perform(get("/api/v1/webhooks").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> channel = JsonPath.read(listed, "$[?(@.id == %d)].auditSink".formatted(channelId));
        assertThat(channel).containsExactly(sinkName);
        List<Object> lifecycle = JsonPath.read(listed, "$[?(@.id == %d)].auditSink".formatted(lifecycleId));
        assertThat(lifecycle).containsOnlyNulls();
    }

    private long createSink(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/audit/sinks")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://siem.invalid/ingest\",\"after\":%d}"
                                .formatted(name, fetchLogRepository.maxId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        sinks.add(id);
        return id;
    }

    private long createSubscriber(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/webhooks")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://hook.invalid/receive\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        subscribers.add(id);
        return id;
    }

    private long ledgerCount(String event) {
        return fetchLogRepository.list().stream()
                .filter(entry -> event.equals(entry.get("event")))
                .count();
    }

    private static EstateReconciliation.Entry entry(EstateReconciliation report, String name) {
        return report.entries().stream()
                .filter(entry -> "webhook".equals(entry.kind()) && name.equals(entry.name()))
                .findFirst()
                .orElseThrow();
    }
}
