package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.webhook.WebhookDispatcher;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import dev.skillsgateway.server.webhook.WebhookSigner;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Lifecycle event webhooks: filtering (GW_0023), signing (GW_0024), retry with backoff (GW_0025). */
class WebhookTests extends AbstractGatewayTest {

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    /** A captured inbound request: everything a receiver would use to authenticate the delivery. */
    private record Received(Map<String, String> headers, String body) {}

    /** Minimal local receiver; {@code status} decides what every delivery gets answered with. */
    private static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private final AtomicInteger status;

        Receiver(int status) throws IOException {
            this.status = new AtomicInteger(status);
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/hook", this::handle);
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    Map.of(
                            WebhookSigner.SIGNATURE_HEADER,
                            String.valueOf(exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER)),
                            WebhookSigner.EVENT_HEADER,
                            String.valueOf(exchange.getRequestHeaders().getFirst(WebhookSigner.EVENT_HEADER)),
                            WebhookSigner.DELIVERY_HEADER,
                            String.valueOf(exchange.getRequestHeaders().getFirst(WebhookSigner.DELIVERY_HEADER))),
                    body));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private long createSubscriber(String name, String url, String events, StringBuilder secretOut) throws Exception {
        String body = mockMvc.perform(post("/api/webhooks")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\",\"events\":\"%s\"}".formatted(name, url, events)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        if (secretOut != null) {
            secretOut.append((String) JsonPath.read(body, "$.secret"));
        }
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /**
     * The registry is what the portal offers instead of a text box, so what it omits matters as
     * much as what it lists: {@code audit.export} is provisioned by creating an export sink, and a
     * lifecycle subscriber must never be able to pick it out of a list and receive ledger content.
     */
    @Test
    @SVCs({"SVC_GW_0088"})
    void the_event_registry_lists_every_dispatchable_event_and_never_the_export_event() throws Exception {
        String body = mockMvc.perform(get("/api/webhooks/events").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> served = JsonPath.read(body, "$");

        assertThat(served).containsExactlyElementsOf(WebhookEvent.ALL);
        assertThat(served).doesNotContain(WebhookEvent.AUDIT_EXPORT);
        // Every event the dispatcher can emit is offerable: a filter cannot be composed for an
        // event the registry hides, so a gap here is an event no subscriber could ever select.
        assertThat(served).contains("snapshot.ingested", "snapshot.approved", "snapshot.revoked");
    }

    @Test
    @SVCs({"SVC_GW_0023"})
    void lifecycleEventsReachOnlySubscribersFilteringForThem() throws Exception {
        String marketplace = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        long approvedSubscriber =
                createSubscriber(uniqueName("approved"), "https://receiver.invalid/hook", "snapshot.approved", null);
        long rejectedSubscriber =
                createSubscriber(uniqueName("rejected"), "https://receiver.invalid/hook", "snapshot.rejected", null);

        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}"
                                .formatted(marketplace, upstream.toUri().toString())))
                .andExpect(status().isCreated());
        String ingested = mockMvc.perform(post("/api/marketplaces/%s/ingest".formatted(marketplace))
                        .with(oidcLogin()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long snapshotId = ((Number) JsonPath.read(ingested, "$.id")).longValue();
        String sha = JsonPath.read(ingested, "$.sha");
        mockMvc.perform(post("/api/snapshots/%d/approve".formatted(snapshotId)).with(oidcLogin()))
                .andExpect(status().isOk());

        List<WebhookDelivery> forApproved = deliveryRepository.listBySubscriber(approvedSubscriber);
        assertThat(forApproved).hasSize(1);
        WebhookDelivery delivery = forApproved.getFirst();
        assertThat(delivery.event()).isEqualTo("snapshot.approved");
        assertThat(delivery.state()).isEqualTo(WebhookDelivery.PENDING);
        assertThat(delivery.payload())
                .contains("\"marketplace\":\"%s\"".formatted(marketplace))
                .contains("\"snapshotId\":%d".formatted(snapshotId))
                .contains("\"sha\":\"%s\"".formatted(sha))
                .contains("\"state\":\"approved\"");
        assertThat(deliveryRepository.listBySubscriber(rejectedSubscriber)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_0024"})
    void deliveriesAreSignedWithTheShowOnceSecret() throws Exception {
        try (Receiver receiver = new Receiver(200)) {
            StringBuilder secret = new StringBuilder();
            String name = uniqueName("signed");
            long subscriberId = createSubscriber(name, receiver.url(), "*", secret);
            assertThat(secret.toString()).startsWith("whsec_");

            webhookService.emit("snapshot.approved", "corp", 42L, "abc123", "approved", "alice");
            assertThat(webhookDispatcher.dispatchDue()).isPositive();

            assertThat(receiver.received).hasSize(1);
            Received request = receiver.received.getFirst();
            String expected = new WebhookSigner().sign(secret.toString(), request.body());
            assertThat(request.headers().get(WebhookSigner.SIGNATURE_HEADER)).isEqualTo(expected);
            assertThat(expected).startsWith("sha256=");
            assertThat(request.headers().get(WebhookSigner.EVENT_HEADER)).isEqualTo("snapshot.approved");
            assertThat(request.headers().get(WebhookSigner.DELIVERY_HEADER)).isNotBlank();

            List<WebhookDelivery> deliveries = deliveryRepository.listBySubscriber(subscriberId);
            assertThat(deliveries).singleElement().satisfies(delivery -> {
                assertThat(delivery.state()).isEqualTo(WebhookDelivery.DELIVERED);
                assertThat(delivery.lastStatus()).isEqualTo(200);
            });

            String listed = mockMvc.perform(get("/api/webhooks").with(oidcLogin()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(listed).contains(name).doesNotContain(secret.toString());
        }
    }

    @Test
    @SVCs({"SVC_GW_0025"})
    void failingDeliveryIsRetriedWithBackoffAndFinallyFails() throws Exception {
        try (Receiver receiver = new Receiver(500)) {
            long subscriberId = createSubscriber(uniqueName("failing"), receiver.url(), "*", null);
            webhookService.emit("snapshot.ingested", "corp", 7L, "def456", "held", "alice");

            WebhookDelivery first = dispatchOnce(subscriberId, 1);
            waitUntilDue(first);
            WebhookDelivery second = dispatchOnce(subscriberId, 2);
            // The scheduled interval grows: measured from the row itself, not from wall clock,
            // so a slow attempt cannot make the comparison flaky.
            assertThat(scheduledBackoff(second)).isGreaterThan(scheduledBackoff(first));

            waitUntilDue(second);
            assertThat(webhookDispatcher.dispatchDue()).isPositive();
            WebhookDelivery exhausted =
                    deliveryRepository.listBySubscriber(subscriberId).getFirst();
            assertThat(exhausted.state()).isEqualTo(WebhookDelivery.FAILED);
            assertThat(exhausted.attempts()).isEqualTo(3);
            assertThat(exhausted.lastStatus()).isEqualTo(500);
            assertThat(exhausted.lastError()).contains("500");
            assertThat(receiver.received).hasSize(3);
        }
    }

    /** Dispatches one pass and returns the delivery, asserting the attempt was recorded. */
    private WebhookDelivery dispatchOnce(long subscriberId, int expectedAttempts) {
        assertThat(webhookDispatcher.dispatchDue()).isPositive();
        WebhookDelivery delivery =
                deliveryRepository.listBySubscriber(subscriberId).getFirst();
        assertThat(delivery.state()).isEqualTo(WebhookDelivery.PENDING);
        assertThat(delivery.attempts()).isEqualTo(expectedAttempts);
        assertThat(delivery.lastStatus()).isEqualTo(500);
        return delivery;
    }

    /** The interval the dispatcher scheduled: both timestamps are written by the same update. */
    private static Duration scheduledBackoff(WebhookDelivery delivery) {
        return Duration.between(delivery.updatedAt(), delivery.nextAttemptAt());
    }

    private static void waitUntilDue(WebhookDelivery delivery) throws InterruptedException {
        long millis = Duration.between(Instant.now(), delivery.nextAttemptAt()).toMillis();
        if (millis > 0) {
            Thread.sleep(millis + 50);
        }
    }
}
