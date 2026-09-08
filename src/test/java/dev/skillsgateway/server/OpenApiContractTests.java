package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import dev.skillsgateway.server.api.OpenAPI;
import dev.skillsgateway.server.persistence.WebhookDelivery;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import dev.skillsgateway.server.webhook.WebhookSigner;
import io.github.reqstool.annotations.SVCs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;

/**
 * The contract the API keeps with everything built from it: the served document says which release
 * it describes, the copy published in the repository is the same document, and the workflow that
 * refuses an undeclared breaking change carries the rules that make it mean something.
 */
class OpenApiContractTests extends AbstractGatewayTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path PUBLISHED = REPO_ROOT.resolve("src/main/frontend/openapi.json");
    private static final String OASDIFF_SEVERITY_LEVELS = ".oasdiff-severity-levels.txt";

    private static final String REGENERATE =
            "cp target/openapi.json src/main/frontend/openapi.json && (cd src/main/frontend && "
                    + "pnpm run gen:api-types)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private WebhookService webhookService;

    private String servedDocument() throws Exception {
        return mockMvc.perform(get("/v3/api-docs").with(oidcLogin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    @SVCs({"SVC_GW_API_0002"})
    void servedDocumentDeclaresTheBuildsVersion() throws Exception {
        String served = JsonPath.read(servedDocument(), "$.info.version");

        // BuildProperties is only present when the build ran build-info; if it silently stopped
        // running, the fallback would keep the document parseable and hide that from us.
        assertThat(buildProperties.getVersion())
                .as("build-info.properties present, so the document can report a real release")
                .isNotBlank()
                .isNotEqualTo(OpenAPI.UNKNOWN_VERSION);
        assertThat(served)
                .as("the served document declares the release it describes, not a hand-written label")
                .isEqualTo(buildProperties.getVersion())
                .isNotEqualTo("v1")
                .isNotEqualTo(OpenApiSnapshot.PLACEHOLDER_VERSION);
    }

    /** The check itself, so the negative test below can run the real one rather than an imitation. */
    private static void assertPublishedIsCurrent(String published, String servedDocument) throws IOException {
        assertThat(published)
                .as(
                        "%s is stale — the portal's types and mocks would describe an API the gateway no"
                                + " longer has, and the compatibility gate would diff a document nobody"
                                + " serves. Regenerate it:%n%n  %s%n",
                        PUBLISHED, REGENERATE)
                .isEqualTo(OpenApiSnapshot.publishedForm(servedDocument));
    }

    @Test
    @SVCs({"SVC_GW_API_0003"})
    void publishedDocumentMatchesTheServedOne() throws Exception {
        assertPublishedIsCurrent(Files.readString(PUBLISHED), servedDocument());
    }

    @Test
    void theStalenessCheckCanActuallyFail() throws Exception {
        // A check nobody has seen fail is a check nobody should trust: drop one endpoint from the
        // published copy and the real assertion above must go red.
        String served = servedDocument();
        String drifted = OpenApiSnapshot.publishedForm(served).replace("\"/api/tokens\"", "\"/api/tokenz\"");

        assertThatThrownBy(() -> assertPublishedIsCurrent(drifted, served))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is stale")
                .hasMessageContaining("gen:api-types");
    }

    @Test
    @SVCs({"SVC_GW_API_0004"})
    void contractWorkflowCarriesTheBreakingChangeContract() throws IOException {
        String workflow = Files.readString(REPO_ROOT.resolve(".github/workflows/api-contract.yml"));

        // a title fixed after a red check has to re-run, as in check-semantic-pr.yml
        assertThat(workflow).contains("types: [opened, edited, reopened, synchronize]");

        // the baseline is what this branch forked from, not whatever main has become since
        assertThat(workflow).contains("fetch-depth: 0");
        assertThat(workflow).contains("git merge-base");
        assertThat(workflow).contains("src/main/frontend/openapi.json");

        // the gate itself, and the two conditions that let a deliberate break through
        assertThat(workflow).contains("oasdiff");
        assertThat(workflow).contains("BREAKING CONTRACT");
        assertThat(workflow).contains("BREAKING CHANGE");

        // a refusal that only refuses teaches nobody: the escape is named in the failure
        assertThat(workflow).contains("/api/v1");
    }

    // ---------------------------------------------------------------------------------------
    // The lifecycle event deliveries (GW_API_0005, GW_API_0006). Each assertion below is a private
    // method so the negative tests can drive the real check from a mutated document rather
    // than an imitation of it.
    // ---------------------------------------------------------------------------------------

    /** Where springdoc puts a payload once the events endpoint's response reaches it. */
    private static final String EVENT_PAYLOAD = "EventPayload";

    private static final String APPROVAL_PENDING_PAYLOAD = "ApprovalPendingPayload";

    private static final List<String> PAYLOAD_FIELDS =
            List.of("event", "occurredAt", "marketplace", "snapshotId", "sha", "state", "actor");

    private static final List<String> DELIVERY_HEADERS = List.of(
            WebhookSigner.EVENT_HEADER,
            WebhookSigner.DELIVERY_HEADER,
            WebhookSigner.TIMESTAMP_HEADER,
            WebhookSigner.SIGNATURE_HEADER);

    private static void assertDescribesExactlyTheSubscribableEvents(DocumentContext document) {
        Map<String, Object> webhooks = document.read("$.webhooks");

        assertThat(webhooks.keySet())
                .as("every subscribable lifecycle event is described, and only those — an event added to"
                        + " the registry cannot be missing from the contract, and the audit export event"
                        + " cannot leak into it")
                .containsExactlyInAnyOrderElementsOf(WebhookEvent.ALL)
                .doesNotContain(WebhookEvent.AUDIT_EXPORT);
    }

    private static void assertEachDeliveryReferencesItsOwnPayload(DocumentContext document) {
        for (String event : WebhookEvent.ALL) {
            String expected =
                    WebhookEvent.SNAPSHOT_APPROVAL_PENDING.equals(event) ? APPROVAL_PENDING_PAYLOAD : EVENT_PAYLOAD;
            assertThat(document.<String>read("$.webhooks['%s'].post.requestBody.content['application/json'].schema.$ref"
                            .formatted(event)))
                    .as("%s delivers the body the service actually emits for it", event)
                    .isEqualTo("#/components/schemas/" + expected);
        }
    }

    private static void assertThePayloadFieldsAreDeclaredAlwaysPresent(DocumentContext document) {
        assertThat(document.<List<String>>read("$.components.schemas.%s.required".formatted(EVENT_PAYLOAD)))
                .as("a field a receiver always gets is declared as one — and a required field's removal is"
                        + " an error to the contract diff, not a warning it lets through")
                .containsExactlyInAnyOrderElementsOf(PAYLOAD_FIELDS);
        assertThat(document.<List<String>>read("$.components.schemas.%s.required".formatted(APPROVAL_PENDING_PAYLOAD)))
                .containsAll(PAYLOAD_FIELDS)
                .contains("vetting");
        assertThat(document.<String>read(
                        "$.components.schemas.%s.properties.snapshotId.format".formatted(EVENT_PAYLOAD)))
                .as("the type the diff would report as narrowed if it changed")
                .isEqualTo("int64");
    }

    private static void assertEachDeliveryDescribesItsTransportHeaders(DocumentContext document) {
        for (String event : WebhookEvent.ALL) {
            List<String> names = document.read("$.webhooks['%s'].post.parameters[*].name".formatted(event));
            assertThat(names)
                    .as("%s describes the headers the dispatcher actually sets", event)
                    .containsAll(DELIVERY_HEADERS);
        }
        assertThat(document.<List<String>>read("$.webhooks[*].post.parameters[?(@.name == '%s')].schema.pattern"
                        .formatted(WebhookSigner.SIGNATURE_HEADER)))
                .as("the signature header's shape is constrained; how the HMAC is computed stays in the guide")
                .isNotEmpty()
                .allSatisfy(pattern -> assertThat(pattern).contains("sha256="));
    }

    private static void assertThePayloadIsReachableFromPaths(DocumentContext document) {
        Map<String, Object> responses = document.read("$.paths['/api/webhooks/events'].get.responses");
        assertThat(responses.toString())
                .as("the events endpoint answers with the registry record, which is what pulls the payloads"
                        + " into the paths surface")
                .contains("#/components/schemas/EventRegistry");
        Map<String, Object> properties = document.read("$.components.schemas.EventRegistry.properties");
        assertThat(properties.toString())
                .as("both payload shapes hang off a response, where the diff rates a removed field an error"
                        + " — on the request side of a webhooks entry it is only a warning (GW_API_0006)")
                .contains("#/components/schemas/" + EVENT_PAYLOAD)
                .contains("#/components/schemas/" + APPROVAL_PENDING_PAYLOAD);
    }

    private static DocumentContext parse(String document) {
        return JsonPath.parse(document);
    }

    /** The served document with one deliberate regression applied, so a real check can be seen failing. */
    private static DocumentContext mutated(String served, Consumer<ObjectNode> regression) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(served);
        regression.accept(root);
        return parse(MAPPER.writeValueAsString(root));
    }

    private static ObjectNode delivery(ObjectNode root, String event) {
        return (ObjectNode) root.required("webhooks").required(event).required("post");
    }

    @Test
    @SVCs({"SVC_GW_API_0005"})
    void theDocumentDescribesEveryLifecycleDelivery() throws Exception {
        DocumentContext document = parse(servedDocument());

        assertDescribesExactlyTheSubscribableEvents(document);
        assertEachDeliveryReferencesItsOwnPayload(document);
        assertThePayloadFieldsAreDeclaredAlwaysPresent(document);
        assertEachDeliveryDescribesItsTransportHeaders(document);
        assertThePayloadIsReachableFromPaths(document);
    }

    @Test
    @SVCs({"SVC_GW_API_0005"})
    void aDeliveryCarriesEveryFieldTheDocumentPromises() throws Exception {
        // The contract is worthless if the document promises a field the wire omits, so the check
        // runs against a real queued delivery rather than against the record's declaration.
        String name = "contract-" + UUID.randomUUID().toString().substring(0, 8);
        webhookService.register(name, "https://receiver.example.com/hook", "*", null, "contract-test");
        List<WebhookDelivery> queued =
                webhookService.emit(WebhookEvent.SNAPSHOT_APPROVED, "corp", 42L, "abc123", "approved", "alice");

        List<String> promised =
                parse(servedDocument()).read("$.components.schemas.%s.required".formatted(EVENT_PAYLOAD));
        JsonNode onTheWire = MAPPER.readTree(queued.getFirst().payload());
        List<String> fieldsSent = new ArrayList<>();
        onTheWire.fieldNames().forEachRemaining(fieldsSent::add);

        assertThat(fieldsSent)
                .as("the bytes the dispatcher sends carry every field the published document declares"
                        + " always present")
                .containsAll(promised);
    }

    @Test
    @SVCs({"SVC_GW_API_0005"})
    void theDeliveryChecksCanActuallyFail() throws Exception {
        // A check nobody has seen fail is a check nobody should trust. Each mutation below is the
        // exact regression its check exists to catch.
        String served = servedDocument();

        assertThatThrownBy(() -> assertDescribesExactlyTheSubscribableEvents(
                        mutated(served, root -> ((ObjectNode) root.required("webhooks"))
                                .remove(WebhookEvent.SNAPSHOT_REJECTED))))
                .as("an event dropped from the contract")
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertEachDeliveryReferencesItsOwnPayload(
                        mutated(served, root -> ((ObjectNode) delivery(root, WebhookEvent.SNAPSHOT_APPROVED)
                                        .required("requestBody")
                                        .required("content")
                                        .required("application/json")
                                        .required("schema"))
                                .put("$ref", "#/components/schemas/Nothing"))))
                .as("a delivery pointed at a body it does not carry")
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertThePayloadFieldsAreDeclaredAlwaysPresent(mutated(served, root -> {
                    ArrayNode required = (ArrayNode) root.required("components")
                            .required("schemas")
                            .required(EVENT_PAYLOAD)
                            .required("required");
                    required.remove(required.size() - 1);
                })))
                .as("a payload field no longer declared always present")
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertEachDeliveryDescribesItsTransportHeaders(mutated(served, root -> ((ArrayNode)
                                delivery(root, WebhookEvent.SNAPSHOT_APPROVED).required("parameters"))
                        .removeAll())))
                .as("a transport header no longer described")
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertThePayloadIsReachableFromPaths(
                        mutated(served, root -> ((ObjectNode) root.required("paths")
                                        .required("/api/webhooks/events")
                                        .required("get")
                                        .required("responses"))
                                .putObject("200"))))
                .as("the payloads no longer reachable from the paths surface the diff rates as a response")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @SVCs({"SVC_GW_API_0006"})
    void theContractGateCoversTheDeliveriesOnTheSameTerms() throws IOException {
        String workflow = Files.readString(REPO_ROOT.resolve(".github/workflows/api-contract.yml"));

        // One gate, one document: the file it diffs is the one that carries the deliveries, so the
        // label and title rules reach them without a second mechanism to trust.
        assertThat(workflow).contains("src/main/frontend/openapi.json");
        assertThat(workflow).contains("BREAKING CONTRACT");
        assertThat(workflow).contains("BREAKING CHANGE");
        assertThat(Files.readString(PUBLISHED))
                .as("the diffed document is the one carrying the lifecycle deliveries")
                .contains("\"webhooks\"")
                .contains(WebhookEvent.SNAPSHOT_APPROVED)
                .contains("\"" + EVENT_PAYLOAD + "\"");
    }

    @Test
    @SVCs({"SVC_GW_API_0004"})
    void removingAResponseFieldIsAnErrorNotAWarning() throws IOException {
        // oasdiff rates removing a response field a warning unless the schema marks the field
        // required, and no response schema here does — so fail-on: ERR let it through (#216).
        assertThat(Files.readString(REPO_ROOT.resolve(".oasdiff.yaml")))
                .as("the severity file the oasdiff step picks up from the repository root")
                .contains("severity-levels: " + OASDIFF_SEVERITY_LEVELS);
        assertThat(Files.readString(REPO_ROOT.resolve(OASDIFF_SEVERITY_LEVELS)))
                .contains("response-optional-property-removed\terr");
    }
}
