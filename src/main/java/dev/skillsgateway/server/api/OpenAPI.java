package dev.skillsgateway.server.api;

import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookSigner;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Skills Gateway",
                        description = """
                                Enterprise gateway for git-distributed AI agent skill marketplaces \
                                (Claude Code plugins, Copilot, Cursor).

                                ## Model

                                Upstream marketplaces are ingested into quarantine as immutable, \
                                SHA-identified snapshots, held until a reviewer approves them, and \
                                served to unmodified git clients over smart-HTTP. Only approved \
                                content is ever fetchable; upstream changes never alter served \
                                content until re-ingested and re-approved (rug-pull protection). \
                                Every git fetch and every administrative action lands in an \
                                append-only audit ledger.

                                ## Quick start

                                1. `POST /api/v1/marketplaces` — register an upstream by clone URL \
                                (http/https only; the gateway pins ingestion to the upstream \
                                default branch — a `ref` cannot be supplied).
                                2. `POST /api/v1/marketplaces/{name}/ingest` — fetch the upstream \
                                into quarantine; the snapshot is `held` (or `rejected` on policy \
                                violation, e.g. non-local plugin sources).
                                3. `GET /api/v1/snapshots/{id}/content` — inspect the plugins and \
                                skills the snapshot ships before deciding.
                                4. `POST /api/v1/snapshots/{id}/approve` — publish it to the facade.
                                5. `POST /api/v1/tokens` — issue a personal access token, then \
                                `git clone http://token:<PAT>@<gateway>/git/{marketplace}`.

                                ## Authentication

                                Browser sessions authenticate via OIDC (the app is its own BFF — \
                                tokens never reach the browser); git clients authenticate with \
                                personal access tokens over the standard credential-helper flow \
                                (Basic auth, username `token`). Tokens are stored as hashes and \
                                shown exactly once at creation.

                                This API is the same surface the admin portal uses. The gateway \
                                also exposes its own CycloneDX SBOM at `/actuator/sbom` and \
                                health at `/actuator/health`.""",
                        contact = @Contact(name = "jimisola", url = "https://github.com/skillsgateway/skillsgateway"),
                        license = @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")),
        externalDocs =
                @ExternalDocumentation(
                        description = "Architecture, ADRs, and requirements (reqstool SSOT)",
                        url = "https://github.com/skillsgateway/skillsgateway#documentation"),
        tags = {
            @Tag(
                    name = "Marketplaces",
                    description = "Register upstream marketplaces and ingest them into held, SHA-pinned snapshots."
                            + " Registration enforces the URL scheme allowlist and the gateway-pinned ref."),
            @Tag(
                    name = "Snapshots",
                    description = "The approval gate: approve or reject held snapshots, inspect their plugin/skill"
                            + " inventory, and retrieve provenance (what was served, from where, who approved)."),
            @Tag(
                    name = "Audit",
                    description = "Append-only ledger of every git facade fetch and every administrative action,"
                            + " each attributed to an identity."),
            @Tag(
                    name = "Tokens",
                    description = "Personal access tokens for git clients: hashed at rest, cleartext shown exactly"
                            + " once, revocable at any time."),
            @Tag(name = "Session", description = "Identity of the authenticated browser session."),
            @Tag(
                    name = "Webhooks",
                    description = "Receivers for marketplace lifecycle events, and the deliveries the gateway"
                            + " sends them. Each event the gateway posts is described under this document's top-level"
                            + " `webhooks` object; `GET /api/v1/webhooks/events` serves the same vocabulary with an"
                            + " example of each body."),
            @Tag(
                    name = "Policy",
                    description = "CEL deny rules evaluated fail-closed at approval time, and the read-only"
                            + " playground that tests an expression against a real snapshot before it is enforced."),
        })
@Configuration(proxyBeanMethods = false)
public class OpenAPI {

    /**
     * Served when {@code build-info.properties} is absent — an IDE run, not a build. Valid semver
     * so a consumer parsing the field does not have to special-case it.
     */
    public static final String UNKNOWN_VERSION = "0.0.0-unknown";

    /**
     * The document reports the release it describes, derived from git state by Nisse rather than
     * maintained by hand. The published snapshot normalises this field away — it changes on every
     * commit, so a snapshot carrying it would differ from the build on every commit.
     */
    @Bean
    @Requirements({"GW_API_0002"})
    OpenApiCustomizer documentVersion(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : UNKNOWN_VERSION;
        return openApi -> openApi.getInfo().setVersion(version);
    }

    /** The component this document points every refusal at. */
    private static final String PROBLEM_DETAIL = "ProblemDetail";

    /**
     * Every non-success response is an RFC 7807 document (GW_API_0007).
     *
     * <p>Derived rather than annotated. springdoc gives a response with no declared schema the
     * operation's success schema, so before this every {@code @ApiResponse(responseCode = "409")}
     * in the codebase — 96 of them — documented a refusal as though it returned the thing it
     * refused to produce. Annotating each one would be 96 chances to forget, and forgetting is
     * invisible: the document stays well-formed and simply lies.
     *
     * <p>The descriptions each {@code @ApiResponse} carries are kept. They say what that particular
     * refusal means, which is the part worth writing by hand; only the body shape is imposed here.
     */
    @Bean
    @Requirements({"GW_API_0007"})
    OpenApiCustomizer refusalsAreProblemDocuments() {
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM_DETAIL, problemDetailSchema());
            openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations()
                    .forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        if (responses == null) {
                            return;
                        }
                        responses.forEach((code, response) -> {
                            if (code.startsWith("4") || code.startsWith("5")) {
                                response.setContent(new Content()
                                        .addMediaType(
                                                MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                                new io.swagger.v3.oas.models.media.MediaType()
                                                        .schema(new Schema<>().$ref(SCHEMA_REF + PROBLEM_DETAIL))));
                            }
                        });
                    }));
        };
    }

    /**
     * RFC 7807, as Spring's {@code ProblemDetail} serializes it. Declared here rather than left to
     * springdoc's reflection: nothing in the codebase returns the type from a mapped method, so
     * there is no signature for it to read.
     */
    private static Schema<?> problemDetailSchema() {
        Schema<?> schema = new io.swagger.v3.oas.models.media.ObjectSchema()
                .description("An RFC 7807 problem document. Every refusal uses this shape, whatever raised it.");
        schema.addProperty(
                "type",
                new StringSchema()
                        .format("uri")
                        .description("A URI identifying the problem type; `about:blank` when the status is the"
                                + " whole story."));
        schema.addProperty("title", new StringSchema().description("A short, human-readable summary of the type."));
        schema.addProperty(
                "status",
                new io.swagger.v3.oas.models.media.IntegerSchema()
                        .description("The HTTP status code, repeated in the body."));
        schema.addProperty(
                "detail",
                new StringSchema()
                        .description("Why this request in particular was refused. The field a client should show a"
                                + " user."));
        schema.addProperty(
                "instance", new StringSchema().format("uri").description("The request path this problem occurred on."));
        schema.setRequired(List.of("status"));
        return schema;
    }

    /** Where a webhook body's schema lives once springdoc has emitted it from the events endpoint. */
    private static final String SCHEMA_REF = "#/components/schemas/";

    /** The component name of the body every lifecycle event but one delivers. */
    private static final String EVENT_PAYLOAD = "EventPayload";

    /** The richer body {@code marketplace.snapshot.approval_pending} delivers. */
    private static final String APPROVAL_PENDING_PAYLOAD = "ApprovalPendingPayload";

    /** The body the {@code marketplace.*} events deliver; no snapshot to name. */
    private static final String MARKETPLACE_PAYLOAD = "MarketplacePayload";

    /**
     * The delivery the gateway sends for each lifecycle event, published in the same document as
     * the REST surface (GW_API_0005) — OpenAPI 3.1's top-level {@code webhooks} object.
     *
     * <p>Generated by looping {@link WebhookEvent#CATALOGUE} rather than written as one
     * {@code @Webhook} annotation per event: an event added to the catalogue cannot then fail to
     * appear in the contract, and the catalogue is also what says which body it carries, so it
     * cannot appear with the wrong one either. That is the drift this exists to close.
     * {@link WebhookEvent#AUDIT_EXPORT} stays out by the same construction that keeps it out of the
     * catalogue — it is provisioned by creating an audit export sink, never by subscribing.
     *
     * <p>The header names come from {@link WebhookSigner}, the constants the dispatcher actually
     * sets, never re-spelled here. How the signature is <em>computed</em> is deliberately absent: a
     * schema can say a header exists and constrain its shape, and the HMAC scheme belongs in the
     * lifecycle-webhooks guide where it is written out with runnable examples.
     */
    @Bean
    @Requirements({"GW_API_0005"})
    OpenApiCustomizer lifecycleWebhookDeliveries() {
        return openApi -> WebhookEvent.ALL.forEach(event -> openApi.addWebhooks(event, delivery(event)));
    }

    private static PathItem delivery(String event) {
        String schema = component(WebhookEvent.shapeOf(event));
        Operation post = new Operation()
                .operationId("webhook-" + event)
                .addTagsItem("Webhooks")
                .summary("Delivery of " + event)
                .description("Sent by the gateway to every enabled subscriber whose event filter includes `" + event
                        + "`. Signed with the subscriber's secret; see the lifecycle webhooks guide for the"
                        + " verification scheme. Retried on failure, so a receiver must de-duplicate on the"
                        + " delivery header.")
                .parameters(deliveryHeaders())
                .requestBody(new RequestBody()
                        .required(true)
                        .description("The event body, serialized once so every retry sends identical bytes.")
                        .content(new Content()
                                .addMediaType(
                                        MediaType.APPLICATION_JSON_VALUE,
                                        new io.swagger.v3.oas.models.media.MediaType()
                                                .schema(new Schema<>().$ref(SCHEMA_REF + schema)))))
                .responses(new ApiResponses()
                        .addApiResponse(
                                "2XX",
                                new ApiResponse()
                                        .description("Accepted. Any 2xx marks the delivery delivered; the body is"
                                                + " not read. Anything else is retried.")));
        return new PathItem().post(post);
    }

    /** The catalogue says which body an event carries; this is the only place that maps it to a name. */
    private static String component(WebhookEvent.Shape shape) {
        return switch (shape) {
            case SNAPSHOT -> EVENT_PAYLOAD;
            case APPROVAL_PENDING -> APPROVAL_PENDING_PAYLOAD;
            case MARKETPLACE -> MARKETPLACE_PAYLOAD;
        };
    }

    private static List<Parameter> deliveryHeaders() {
        return List.of(
                header(
                        WebhookSigner.EVENT_HEADER,
                        "The lifecycle event name, identical to the payload's `event` field.",
                        null),
                header(
                        WebhookSigner.DELIVERY_HEADER,
                        "Delivery id — stable across retries of the same delivery, and the receiver's"
                                + " de-duplication key.",
                        null),
                header(WebhookSigner.TIMESTAMP_HEADER, "Time this attempt was sent, ISO-8601.", null),
                header(
                        WebhookSigner.SIGNATURE_HEADER,
                        "HMAC of the exact request body under the subscriber's signing secret, in the form"
                                + " `sha256=<hex>`. The guide states how it is computed and compared.",
                        "^sha256=[0-9a-f]{64}$"));
    }

    private static Parameter header(String name, String description, String pattern) {
        StringSchema schema = new StringSchema();
        if (pattern != null) {
            schema.pattern(pattern);
        }
        return new Parameter()
                .in("header")
                .name(name)
                .description(description)
                .required(true)
                .schema(schema);
    }
}
