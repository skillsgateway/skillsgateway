package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.audit.AuditController;
import dev.skillsgateway.server.audit.AuditExportService;
import dev.skillsgateway.server.persistence.AuditSink;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.webhook.WebhookEvent;
import io.github.reqstool.annotations.SVCs;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Audit ledger export: the NDJSON pull stream (GW_0027), cursor-tracking push sinks with
 * at-least-once delivery (GW_0028), and cursor replay (GW_0029).
 */
class AuditExportTests extends AbstractGatewayTest {

    @Autowired
    private AuditExportService exportService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    /** One exported page: the lines of the stream and the cursor a consumer resumes from. */
    private record Page(List<String> lines, long cursor) {}

    /**
     * The export is a streaming response, so MockMvc needs the async dispatch to see the body;
     * the cursor header is on the response either way.
     *
     * <p>{@code asyncStarted()} only asserts that async processing began — the streaming body may
     * still be written from the MVC async thread. Dispatching at that point re-enters the filter
     * chain on the test thread while the async thread is mutating the same unsynchronized
     * {@code MockHttpServletResponse}, which intermittently throws
     * {@code ConcurrentModificationException}. {@code getAsyncResult()} blocks until the streaming
     * body has completed, so the dispatch is safe.
     */
    private Page export(long after, Integer limit) throws Exception {
        String uri = "/api/audit/export?after=" + after + (limit == null ? "" : "&limit=" + limit);
        MvcResult started = mockMvc.perform(get(uri).with(oidcLogin()))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult();
        MvcResult finished = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = finished.getResponse().getContentAsString();
        String cursor = finished.getResponse().getHeader(AuditController.CURSOR_HEADER);
        assertThat(cursor).as("resume cursor header").isNotNull();
        List<String> lines = body.isEmpty()
                ? List.of()
                : Arrays.stream(body.split("\n"))
                        .filter(line -> !line.isBlank())
                        .toList();
        return new Page(lines, Long.parseLong(cursor));
    }

    private long createSink(String name) throws Exception {
        String body = mockMvc.perform(post("/api/audit/sinks")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://siem.invalid/ingest\",\"after\":%d}"
                                .formatted(name, fetchLogRepository.maxId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(body, "$.secret")).startsWith("whsec_");
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void appendLedgerEntries(String marketplace, int count) {
        for (int i = 0; i < count; i++) {
            fetchLogRepository.append("admin", "alice", marketplace, "export-fixture-" + i, null, null);
        }
    }

    @Test
    @SVCs({"SVC_GW_0027"})
    void ledgerStreamsAsNewlineDelimitedJsonFromACursor() throws Exception {
        long start = fetchLogRepository.maxId();
        String marketplace = uniqueName("ndjson");
        appendLedgerEntries(marketplace, 3);

        Page first = export(start, null);
        assertThat(first.lines()).hasSize(3);
        // One JSON object per line, in ascending ledger sequence.
        List<Long> ids = first.lines().stream()
                .map(line -> ((Number) JsonPath.read(line, "$.id")).longValue())
                .toList();
        assertThat(ids).isSorted().hasSize(3);
        assertThat(first.cursor()).isEqualTo(ids.getLast());
        assertThat((String) JsonPath.read(first.lines().getFirst(), "$.marketplace"))
                .isEqualTo(marketplace);
        assertThat((String) JsonPath.read(first.lines().getFirst(), "$.event")).isEqualTo("export-fixture-0");

        // Resuming from the returned cursor yields only what was appended afterwards.
        appendLedgerEntries(marketplace, 1);
        Page second = export(first.cursor(), null);
        assertThat(second.lines()).hasSize(1);
        assertThat(((Number) JsonPath.read(second.lines().getFirst(), "$.id")).longValue())
                .isGreaterThan(first.cursor());

        // Caught up: no entries, and the cursor stands still.
        Page third = export(second.cursor(), null);
        assertThat(third.lines()).isEmpty();
        assertThat(third.cursor()).isEqualTo(second.cursor());
    }

    @Test
    @SVCs({"SVC_GW_0028"})
    void sinkReceivesTheEntriesAfterItsPositionAndAdvancesPastThem() throws Exception {
        long sinkId = createSink(uniqueName("siem"));
        AuditSink sink = exportService.findSink(sinkId).orElseThrow();
        long start = sink.cursorPosition();
        String marketplace = uniqueName("push");
        appendLedgerEntries(marketplace, 2);
        // Registering the sink is itself an audited action, so the batch is at least the two above.
        long head = fetchLogRepository.maxId();

        assertThat(exportService.exportPass()).isPositive();

        List<WebhookDelivery> deliveries = deliveryRepository.listBySubscriber(sink.subscriberId());
        assertThat(deliveries).hasSize(1);
        WebhookDelivery delivery = deliveries.getFirst();
        assertThat(delivery.event()).isEqualTo(WebhookEvent.AUDIT_EXPORT);
        assertThat(delivery.state()).isEqualTo(WebhookDelivery.PENDING);
        assertThat(((Number) JsonPath.read(delivery.payload(), "$.fromCursor")).longValue())
                .isEqualTo(start);
        assertThat(((Number) JsonPath.read(delivery.payload(), "$.toCursor")).longValue())
                .isEqualTo(head);
        assertThat(delivery.payload()).contains(marketplace).contains("export-fixture-0");

        // The cursor advanced to the last entry in the batch...
        assertThat(exportService.findSink(sinkId).orElseThrow().cursorPosition())
                .isEqualTo(head);
        // ...so a pass with nothing new enqueues nothing.
        exportService.exportPass();
        assertThat(deliveryRepository.listBySubscriber(sink.subscriberId())).hasSize(1);
    }

    @Test
    @SVCs({"SVC_GW_0029"})
    void resettingTheCursorRedeliversTheEntriesAfterIt() throws Exception {
        long sinkId = createSink(uniqueName("replay"));
        AuditSink sink = exportService.findSink(sinkId).orElseThrow();
        long start = sink.cursorPosition();
        appendLedgerEntries(uniqueName("replayed"), 2);
        exportService.exportPass();
        assertThat(deliveryRepository.listBySubscriber(sink.subscriberId())).hasSize(1);

        String body = mockMvc.perform(put("/api/audit/sinks/%d/cursor".formatted(sinkId))
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"after\":%d}".formatted(start)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.cursorPosition")).longValue())
                .isEqualTo(start);

        exportService.exportPass();
        List<WebhookDelivery> deliveries = deliveryRepository.listBySubscriber(sink.subscriberId());
        assertThat(deliveries).hasSize(2);
        assertThat(((Number) JsonPath.read(deliveries.getLast().payload(), "$.fromCursor")).longValue())
                .isEqualTo(start);
        assertThat(deliveries.getLast().payload()).contains("export-fixture-0");
    }
}
