package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.persistence.WebhookDeliveryRepository;
import dev.skillsgateway.server.persistence.WebhookSubscriber;
import dev.skillsgateway.server.persistence.WebhookSubscriberRepository;
import dev.skillsgateway.server.vetting.RevetService;
import dev.skillsgateway.server.vetting.Vetter;
import dev.skillsgateway.server.vetting.VettingService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Lifecycle event webhooks: filtering (GW_WEBHOOK_0001), signing (GW_WEBHOOK_0002), retry with backoff (GW_WEBHOOK_0003),
 * and the approval-pending announcement (GW_WEBHOOK_0006, GW_WEBHOOK_0007).
 */
class WebhookTests extends AbstractGatewayTest {

    /**
     * A shaped AWS access key id that belongs to nobody — enough to make the secret-scan vetter
     * block, which is what gives the approval-pending payload a summary worth asserting on.
     */
    private static final String PLANTED_SECRET = """
            # Deployment notes

                AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            """;

    /** The payload's top-level field set: the contract a receiver writes its parser against. */
    private static final List<String> PAYLOAD_FIELDS =
            List.of("event", "occurredAt", "marketplace", "snapshotId", "sha", "state", "actor", "vetting");

    /** The marketplace payload's top-level field set: the four shared fields, plus what changed. */
    private static final List<String> MARKETPLACE_PAYLOAD_FIELDS =
            List.of("event", "occurredAt", "marketplace", "actor", "detail");

    /** The spellings the namespace replaced; none of them may still be offered or accepted. */
    private static final List<String> LEGACY_EVENT_NAMES = List.of(
            "snapshot.ingested",
            "snapshot.approved",
            "snapshot.rejected",
            "snapshot.soft_deleted",
            "snapshot.restored",
            "snapshot.vetted",
            "snapshot.revet_violation",
            "snapshot.revoked",
            "snapshot.approval_pending");

    private static final String TOGGLE_REASON_SCOPED = "vendor keys, expected";

    private static final String TOGGLE_REASON_GLOBAL = "kept on across the estate";

    /** The vetting summary's field set — counts, names and identifiers, and nothing else. */
    private static final List<String> SUMMARY_FIELDS =
            List.of("runId", "outcome", "recordedOutcome", "blockingVetters", "uncoveredFindings", "waivedFindings");

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private SkillsGatewayProperties properties;

    @Autowired
    private RevetService revetService;

    @Autowired
    private WebhookSubscriberRepository subscriberRepository;

    @Autowired
    private VettingService vettingService;

    /**
     * These tests drive the real dispatch pass, which takes the oldest {@code batchSize} due
     * deliveries across the whole shared database. A subscriber left registered by an earlier test
     * class goes on collecting one delivery per event the rest of the suite emits, and once that
     * backlog fills the batch this class's own delivery is never reached -- surfacing here as a
     * delivery that was silently never attempted. Fail on the cause instead.
     */
    @BeforeEach
    void backlogLeavesRoomInTheDispatchBatch() {
        int batchSize = properties.webhooks().batchSize();
        assertThat(deliveryRepository.dueIds(batchSize))
                .describedAs("due webhook deliveries left behind by earlier test classes")
                .hasSizeLessThan(batchSize);
    }

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
    @SVCs({"SVC_GW_WEBHOOK_0005"})
    void the_event_registry_lists_every_dispatchable_event_and_never_the_export_event() throws Exception {
        String body = mockMvc.perform(get("/api/webhooks/events").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The registry answers with an object, not a bare array: it carries the vocabulary and an
        // example of each delivery body, so a receiver author reads both from one place (GW_API_0005).
        List<String> served = JsonPath.read(body, "$.events");

        assertThat(served).containsExactlyElementsOf(WebhookEvent.ALL);
        assertThat(served).doesNotContain(WebhookEvent.AUDIT_EXPORT);
        // Every event the dispatcher can emit is offerable: a filter cannot be composed for an
        // event the registry hides, so a gap here is an event no subscriber could ever select.
        assertThat(served)
                .contains(
                        "marketplace.snapshot.ingested",
                        "marketplace.snapshot.approved",
                        "marketplace.snapshot.revoked");
    }

    /**
     * The announcement an external review pipeline subscribes to (GW_WEBHOOK_0006). Three facts in one
     * arrangement, because they are the same fact from three sides: the subscriber that asked for
     * it gets exactly one delivery for a held snapshot, a subscriber that asked for something else
     * gets none, and the same snapshot approved and then re-vetted produces no second announcement
     * — "awaiting a human" is about the held state, not about a chain run having happened.
     */
    @Test
    @SVCs({"SVC_GW_WEBHOOK_0006"})
    void a_held_snapshot_announces_itself_only_to_the_subscribers_that_asked() throws Exception {
        long pendingSubscriber = createSubscriber(
                uniqueName("pending"), "https://receiver.invalid/hook", WebhookEvent.SNAPSHOT_APPROVAL_PENDING, null);
        long elsewhereSubscriber = createSubscriber(
                uniqueName("elsewhere"), "https://receiver.invalid/hook", "marketplace.snapshot.rejected", null);

        String served = mockMvc.perform(get("/api/webhooks/events").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((List<String>) JsonPath.read(served, "$.events")).contains(WebhookEvent.SNAPSHOT_APPROVAL_PENDING);

        String marketplace = uniqueName("pendinghook");
        Registered registered = registerAndIngest(marketplace, createUpstream(DEFAULT_MANIFEST));

        List<WebhookDelivery> announced = deliveryRepository.listBySubscriber(pendingSubscriber);
        assertThat(announced).hasSize(1);
        WebhookDelivery delivery = announced.getFirst();
        assertThat(delivery.event()).isEqualTo(WebhookEvent.SNAPSHOT_APPROVAL_PENDING);
        assertThat(delivery.state()).isEqualTo(WebhookDelivery.PENDING);
        assertThat(delivery.payload())
                .contains("\"marketplace\":\"%s\"".formatted(marketplace))
                .contains("\"snapshotId\":%d".formatted(registered.snapshot().id()))
                .contains("\"sha\":\"%s\"".formatted(registered.snapshot().sha()))
                .contains("\"state\":\"held\"");
        assertThat(deliveryRepository.listBySubscriber(elsewhereSubscriber)).isEmpty();

        // The negative half: the chain runs again over the very same content, and the only thing
        // that has changed is that a human already decided. No second announcement.
        approve(registered.snapshot().id());
        revetService.revetSnapshot(registered.snapshot().id(), "alice");
        assertThat(deliveryRepository.listBySubscriber(pendingSubscriber))
                .describedAs("an approved snapshot is not awaiting anyone")
                .hasSize(1);
    }

    /**
     * The trust-boundary half (GW_WEBHOOK_0007): the event says a blocked snapshot is waiting, in enough
     * detail to triage it, and says nothing about what the vetters actually found. A webhook
     * target is authorised by a URL scheme allowlist, not by an identity, so the finding messages
     * and the paths they name stay behind the authenticated vetting endpoint.
     */
    @Test
    @SVCs({"SVC_GW_WEBHOOK_0007"})
    void the_approval_pending_payload_summarises_the_run_and_discloses_no_content() throws Exception {
        long subscriber = createSubscriber(
                uniqueName("summary"), "https://receiver.invalid/hook", WebhookEvent.SNAPSHOT_APPROVAL_PENDING, null);

        Registered blocked = registerAndIngest(
                uniqueName("blockedhook"),
                createUpstream(DEFAULT_MANIFEST, Map.of("plugins/hello/DEPLOY.md", PLANTED_SECRET)));

        List<WebhookDelivery> announced = deliveryRepository.listBySubscriber(subscriber);
        assertThat(announced).hasSize(1);
        String payload = announced.getFirst().payload();

        Map<String, Object> body = new ObjectMapper().readValue(payload, Map.class);
        // Exact, not "contains": a removed or renamed field fails here, and so does an added one,
        // which is the point — the payload is a contract, so changing it is a decision.
        assertThat(body).containsOnlyKeys(PAYLOAD_FIELDS.toArray(String[]::new));
        assertThat(body.get("state")).isEqualTo("held");

        Map<String, Object> vetting = (Map<String, Object>) body.get("vetting");
        assertThat(vetting).containsOnlyKeys(SUMMARY_FIELDS.toArray(String[]::new));
        assertThat(((Number) vetting.get("runId")).longValue()).isPositive();
        assertThat(vetting.get("outcome")).isEqualTo("BLOCKED");
        assertThat(vetting.get("recordedOutcome")).isEqualTo("BLOCKED");
        assertThat((List<String>) vetting.get("blockingVetters")).contains("secret-scan");
        assertThat(((Number) vetting.get("uncoveredFindings")).intValue()).isPositive();
        assertThat(((Number) vetting.get("waivedFindings")).intValue()).isZero();

        // The adversarial assertion: nothing from inside quarantine reached the wire.
        assertThat(payload)
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .doesNotContain("aws-access-key-id")
                .doesNotContain("DEPLOY.md")
                .doesNotContain("plugins/hello");
        assertThat(blocked.snapshot().state()).isEqualTo("held");
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0001"})
    void lifecycleEventsReachOnlySubscribersFilteringForThem() throws Exception {
        String marketplace = uniqueName("corp");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        long approvedSubscriber = createSubscriber(
                uniqueName("approved"), "https://receiver.invalid/hook", "marketplace.snapshot.approved", null);
        long rejectedSubscriber = createSubscriber(
                uniqueName("rejected"), "https://receiver.invalid/hook", "marketplace.snapshot.rejected", null);

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
        assertThat(delivery.event()).isEqualTo("marketplace.snapshot.approved");
        assertThat(delivery.state()).isEqualTo(WebhookDelivery.PENDING);
        assertThat(delivery.payload())
                .contains("\"marketplace\":\"%s\"".formatted(marketplace))
                .contains("\"snapshotId\":%d".formatted(snapshotId))
                .contains("\"sha\":\"%s\"".formatted(sha))
                .contains("\"state\":\"approved\"");
        assertThat(deliveryRepository.listBySubscriber(rejectedSubscriber)).isEmpty();
    }

    @Test
    @SVCs({"SVC_GW_WEBHOOK_0002"})
    void deliveriesAreSignedWithTheShowOnceSecret() throws Exception {
        try (Receiver receiver = new Receiver(200)) {
            StringBuilder secret = new StringBuilder();
            String name = uniqueName("signed");
            long subscriberId = createSubscriber(name, receiver.url(), "*", secret);
            assertThat(secret.toString()).startsWith("whsec_");

            webhookService.emit("marketplace.snapshot.approved", "corp", 42L, "abc123", "approved", "alice");
            assertThat(webhookDispatcher.dispatchDue()).isPositive();

            assertThat(receiver.received).hasSize(1);
            Received request = receiver.received.getFirst();
            String expected = new WebhookSigner().sign(secret.toString(), request.body());
            assertThat(request.headers().get(WebhookSigner.SIGNATURE_HEADER)).isEqualTo(expected);
            assertThat(expected).startsWith("sha256=");
            assertThat(request.headers().get(WebhookSigner.EVENT_HEADER)).isEqualTo("marketplace.snapshot.approved");
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
    @SVCs({"SVC_GW_WEBHOOK_0003"})
    void failingDeliveryIsRetriedWithBackoffAndFinallyFails() throws Exception {
        try (Receiver receiver = new Receiver(500)) {
            long subscriberId = createSubscriber(uniqueName("failing"), receiver.url(), "*", null);
            webhookService.emit("marketplace.snapshot.ingested", "corp", 7L, "def456", "held", "alice");

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

    /**
     * The namespace (GW_WEBHOOK_0008), from the three sides it can be observed from: the vocabulary
     * the registry offers, the filter validator that refuses the old spelling, and the delivery
     * that carries the new name in its header and its body.
     *
     * <p>A fourth side used to be here — a data migration that rewrote a filter stored under an old
     * spelling. GW_WEBHOOK_0008 no longer promises that repair: it was carried by a versioned
     * migration, and while the project is pre-1.0 the schema is a single V1__init.sql applied to an
     * empty database, so there are no stored filters for a migration to repair.
     */
    @Test
    @SVCs({"SVC_GW_WEBHOOK_0008"})
    void every_subscribable_event_is_namespaced_and_a_legacy_spelling_is_refused() throws Exception {
        String served = mockMvc.perform(get("/api/webhooks/events").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> offered = JsonPath.read(served, "$.events");
        assertThat(offered)
                .as("every subscribable event lives under the subject it is about")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event).startsWith("marketplace."));
        assertThat(offered).doesNotContainAnyElementsOf(LEGACY_EVENT_NAMES);

        // An old spelling is refused, not silently accepted and then never matched.
        mockMvc.perform(post("/api/webhooks")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"https://receiver.invalid/hook\",\"events\":\"%s\"}"
                                .formatted(uniqueName("legacy"), "snapshot.approved")))
                .andExpect(status().isBadRequest());

        WebhookSubscriber subscriber = subscriberRepository.create(
                uniqueName("ns"),
                "https://receiver.invalid/hook",
                "whsec_ns",
                WebhookEvent.SNAPSHOT_APPROVED + "," + WebhookEvent.SNAPSHOT_REVOKED);
        WebhookSubscriber wildcard = subscriberRepository.create(
                uniqueName("wild"), "https://receiver.invalid/hook", "whsec_wild", WebhookSubscriber.ALL_EVENTS);
        WebhookSubscriber sink = subscriberRepository.create(
                uniqueName("sink"), "https://receiver.invalid/hook", "whsec_sink", WebhookEvent.AUDIT_EXPORT);

        try {
            assertThat(subscriber.subscribesTo(WebhookEvent.SNAPSHOT_APPROVED))
                    .as("a filter written in the published vocabulary matches the event it names")
                    .isTrue();
            assertThat(subscriberRepository
                            .findById(wildcard.id())
                            .orElseThrow()
                            .events())
                    .as("a wildcard filter names no event")
                    .isEqualTo(WebhookSubscriber.ALL_EVENTS);
            assertThat(subscriberRepository.findById(sink.id()).orElseThrow().events())
                    .as("audit.export is about the ledger, not a marketplace, and is not namespaced under one")
                    .isEqualTo(WebhookEvent.AUDIT_EXPORT);

            // The third side: what actually arrives carries the namespaced name. The approval goes
            // through the API because that is where the emit lives.
            Registered registered = registerAndIngest(uniqueName("nshook"), createUpstream(DEFAULT_MANIFEST));
            mockMvc.perform(post("/api/snapshots/%d/approve"
                                    .formatted(registered.snapshot().id()))
                            .with(oidcLogin()))
                    .andExpect(status().isOk());

            List<WebhookDelivery> delivered = deliveryRepository.listBySubscriber(subscriber.id());
            assertThat(delivered).isNotEmpty();
            assertThat(delivered.getFirst().event()).isEqualTo(WebhookEvent.SNAPSHOT_APPROVED);
            assertThat(delivered.getFirst().payload())
                    .contains("\"event\":\"%s\"".formatted(WebhookEvent.SNAPSHOT_APPROVED));
        } finally {
            // The wildcard subscriber would otherwise collect a delivery for every event the rest
            // of the suite emits and starve the shared dispatch batch — the hazard this class's
            // @BeforeEach guards against.
            subscriberRepository.delete(subscriber.id());
            subscriberRepository.delete(wildcard.id());
            subscriberRepository.delete(sink.id());
        }
    }

    /**
     * The marketplace-level events (GW_WEBHOOK_0009). One arrangement, because the three are the same
     * claim: the emit sits beside the ledger write on the success path, so every successful
     * administrative act announces itself exactly once and every refused one announces nothing.
     *
     * <p>The negative half is the load-bearing one. A receiver that acts on a registration the
     * gateway refused is worse than one that hears nothing at all.
     */
    @Test
    @SVCs({"SVC_GW_WEBHOOK_0009"})
    void registering_updating_and_toggling_announce_themselves_and_a_refused_action_announces_nothing()
            throws Exception {
        long subscriber = createSubscriber(
                uniqueName("estate"),
                "https://receiver.invalid/hook",
                String.join(
                        ",",
                        WebhookEvent.MARKETPLACE_REGISTERED,
                        WebhookEvent.MARKETPLACE_UPDATED,
                        WebhookEvent.MARKETPLACE_VETTER_TOGGLED),
                null);

        String marketplace = uniqueName("estatehook");
        Path upstream = createUpstream(DEFAULT_MANIFEST);
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"%s\"}"
                                .formatted(marketplace, upstream.toUri().toString())))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/marketplaces/{name}/sync", marketplace)
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"scheduled\"}"))
                .andExpect(status().isOk());

        // The global toggle is deliberately enabled=true: it changes nothing about what runs, so it
        // cannot leak into another test's expectations, while still being a gateway-wide act whose
        // scope the event has to report as "-" rather than as a marketplace.
        List<String> chain = vettingService.vetters().stream().map(Vetter::name).toList();
        String globalVetter = chain.getFirst();
        String scopedVetter = chain.get(1);
        toggleVetter(scopedVetter, marketplace, false, TOGGLE_REASON_SCOPED);
        toggleVetter(globalVetter, null, true, TOGGLE_REASON_GLOBAL);

        List<WebhookDelivery> announced = deliveryRepository.listBySubscriber(subscriber);
        assertThat(announced.stream().map(WebhookDelivery::event))
                .containsExactlyInAnyOrder(
                        WebhookEvent.MARKETPLACE_REGISTERED,
                        WebhookEvent.MARKETPLACE_UPDATED,
                        WebhookEvent.MARKETPLACE_VETTER_TOGGLED,
                        WebhookEvent.MARKETPLACE_VETTER_TOGGLED);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> registeredBody =
                mapper.readValue(payloadOf(announced, WebhookEvent.MARKETPLACE_REGISTERED), Map.class);
        // Exact, not "contains": the payload is a contract, so an added field is a decision too.
        assertThat(registeredBody).containsOnlyKeys(MARKETPLACE_PAYLOAD_FIELDS.toArray(String[]::new));
        assertThat(registeredBody.get("event")).isEqualTo(WebhookEvent.MARKETPLACE_REGISTERED);
        assertThat(registeredBody.get("marketplace")).isEqualTo(marketplace);
        assertThat(registeredBody.get("detail")).isEqualTo("origin=" + Marketplace.ORIGIN_UPSTREAM);
        assertThat(String.valueOf(registeredBody.get("actor"))).isNotBlank();
        assertThat(String.valueOf(registeredBody.get("occurredAt"))).isNotBlank();

        Map<String, Object> updatedBody =
                mapper.readValue(payloadOf(announced, WebhookEvent.MARKETPLACE_UPDATED), Map.class);
        assertThat(updatedBody).containsOnlyKeys(MARKETPLACE_PAYLOAD_FIELDS.toArray(String[]::new));
        assertThat(updatedBody.get("marketplace")).isEqualTo(marketplace);
        assertThat(updatedBody.get("detail")).isEqualTo("mode=scheduled");

        List<String> toggles = announced.stream()
                .filter(delivery -> WebhookEvent.MARKETPLACE_VETTER_TOGGLED.equals(delivery.event()))
                .map(WebhookDelivery::payload)
                .toList();
        assertThat(toggles)
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"marketplace\":\"%s\"".formatted(marketplace))
                        .contains("vetter=%s".formatted(scopedVetter))
                        .contains("enabled=false"))
                .anySatisfy(payload -> assertThat(payload)
                        .as("a gateway-wide toggle reports the gateway, not a marketplace")
                        .contains("\"marketplace\":\"-\"")
                        .contains("scope=global"));
        assertThat(toggles)
                .as("the operator's reason is free text and stays in the ledger; the event announces")
                .allSatisfy(payload ->
                        assertThat(payload).doesNotContain(TOGGLE_REASON_SCOPED).doesNotContain(TOGGLE_REASON_GLOBAL));

        int queued = announced.size();

        // A registration refused by the scheme allowlist, a sync-mode change for a marketplace that
        // does not exist, and a toggle of a vetter that does not exist.
        mockMvc.perform(post("/api/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"ftp://evil.invalid/repo.git\"}"
                                .formatted(uniqueName("ftp"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/marketplaces/{name}/sync", uniqueName("ghost"))
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"scheduled\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/vetting/vetters/{name}/toggle", "no-such-vetter")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(deliveryRepository.listBySubscriber(subscriber))
                .as("a refused administrative action queues nothing")
                .hasSize(queued);

        // Leave no subscriber behind to collect the rest of the suite's events (see @BeforeEach).
        subscriberRepository.delete(subscriber);
    }

    private static String payloadOf(List<WebhookDelivery> deliveries, String event) {
        return deliveries.stream()
                .filter(delivery -> event.equals(delivery.event()))
                .map(WebhookDelivery::payload)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no delivery of " + event));
    }

    private void toggleVetter(String vetter, String marketplace, boolean enabled, String reason) throws Exception {
        String body = marketplace == null
                ? "{\"enabled\": %s, \"reason\": \"%s\"}".formatted(enabled, reason)
                : "{\"enabled\": %s, \"marketplace\": \"%s\", \"reason\": \"%s\"}"
                        .formatted(enabled, marketplace, reason);
        mockMvc.perform(put("/api/vetting/vetters/{name}/toggle", vetter)
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
