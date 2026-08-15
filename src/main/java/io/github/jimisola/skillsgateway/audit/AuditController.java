package io.github.jimisola.skillsgateway.audit;

import io.github.jimisola.skillsgateway.admin.AdminAuditLogger;
import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.persistence.AuditSink;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The compliance export surface over the append-only ledger: a newline-delimited stream for pull
 * consumers (GW_0027) and cursor-tracking sinks for push consumers (GW_0028, GW_0029).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final Pattern SINK_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    /** Audit export administration is not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    /** Response header naming the sequence a consumer resumes from. */
    public static final String CURSOR_HEADER = "X-Skills-Gateway-Audit-Cursor";

    private static final String NDJSON = "application/x-ndjson";

    private final AuditExportService exportService;
    private final SkillsGatewayProperties properties;
    private final AdminAuditLogger auditLogger;

    public AuditController(
            AuditExportService exportService, SkillsGatewayProperties properties, AdminAuditLogger auditLogger) {
        this.exportService = exportService;
        this.properties = properties;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "Audit export sink registration request")
    public record CreateSinkRequest(
            @Schema(description = "Gateway-local sink name", example = "siem", pattern = "^[a-z0-9][a-z0-9_-]*$")
            String name,

            @Schema(
                    description = "Target URL batches are POSTed to; scheme must be on the configured"
                            + " allowlist (default http/https)",
                    example = "https://siem.example.com/ingest/skills-gateway")
            String url,

            @Schema(description = "Ledger sequence to start after; omit to start at the beginning", example = "0")
            Long after,

            @Schema(description = "Maximum ledger entries per batch", example = "500")
            Integer batchSize) {}

    /** A sink as an operator sees it: never the signing secret, always how far behind it is. */
    @Schema(description = "A registered audit export sink")
    public record SinkView(
            @Schema(description = "Sink id") long id,
            @Schema(description = "Sink name") String name,
            @Schema(description = "Sink kind") String kind,
            @Schema(description = "Target URL") String url,

            @Schema(description = "Ledger sequence last handed to this sink")
            long cursorPosition,

            @Schema(description = "Highest ledger sequence written so far")
            long ledgerHead,

            @Schema(description = "Ledger entries not yet handed to this sink")
            long behind,

            @Schema(description = "Maximum ledger entries per batch")
            int batchSize,

            @Schema(description = "Whether export passes run for this sink")
            boolean enabled,

            @Schema(description = "Creation time") Instant createdAt) {}

    @Schema(description = "Cursor reset request")
    public record CursorRequest(
            @Schema(description = "Ledger sequence to resume after; entries following it are delivered again")
            long after) {}

    @GetMapping(value = "/export", produces = NDJSON)
    @Requirements({"GW_0027"})
    @Tag(name = "Audit")
    @Operation(
            summary = "Stream the audit ledger as NDJSON",
            description = "Newline-delimited JSON, one ledger entry per line in ascending ledger sequence, for"
                    + " ingestion by an external SIEM. Poll incrementally by passing the sequence from the"
                    + " X-Skills-Gateway-Audit-Cursor response header back as 'after'; an empty body means"
                    + " the consumer is caught up. Entries younger than the commit-settling lag are withheld"
                    + " so a cursor can never step over an in-flight append.")
    @ApiResponse(responseCode = "200", description = "The entries after the cursor, newline-delimited")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false, defaultValue = "0") long after,
            @RequestParam(required = false) Integer limit) {
        SkillsGatewayProperties.AuditExport config = properties.auditExport();
        int page = Math.clamp(limit == null ? config.defaultPageSize() : limit, 1, config.maxPageSize());
        long from = Math.max(after, 0);
        long endCursor = exportService.exportEndCursor(from, page);
        StreamingResponseBody body = out -> exportService.streamNdjson(from, endCursor, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON))
                .header(CURSOR_HEADER, Long.toString(endCursor))
                .body(body);
    }

    @PostMapping("/sinks")
    @Requirements({"GW_0028"})
    @Tag(name = "Audit")
    @Operation(
            summary = "Register an audit export sink",
            description = "Registers a push consumer of the ledger. Batches are POSTed to the target URL by the"
                    + " same signed, retried delivery machinery as lifecycle webhooks; the signing secret is"
                    + " returned exactly once, in this response.")
    @ApiResponse(responseCode = "201", description = "Sink registered; response carries the show-once secret")
    @ApiResponse(responseCode = "400", description = "Disallowed URL scheme")
    @ApiResponse(responseCode = "409", description = "A sink or webhook subscriber with that name already exists")
    @ApiResponse(responseCode = "422", description = "Invalid sink name")
    public ResponseEntity<AuditExportService.CreatedSink> createSink(
            @RequestBody CreateSinkRequest request, Authentication authentication) {
        if (request.name() == null || !SINK_NAME.matcher(request.name()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "name must match " + SINK_NAME.pattern());
        }
        requireAllowlistedScheme(request.url());
        if (exportService.findSinkByName(request.name()).isPresent()
                || exportService.channelNameTaken(request.name())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "sink '%s' already exists".formatted(request.name()));
        }
        int batchSize = Math.clamp(
                request.batchSize() == null ? properties.auditExport().batchSize() : request.batchSize(),
                1,
                properties.auditExport().maxPageSize());
        AuditExportService.CreatedSink created = exportService.createWebhookSink(
                request.name(), request.url(), request.after() == null ? 0 : request.after(), batchSize);
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "audit-sink-created", null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/sinks")
    @Tag(name = "Audit")
    @Operation(
            summary = "List audit export sinks",
            description = "Every registered sink with its target, its position in the ledger, and how many"
                    + " entries it still has to receive. Signing secrets are never returned.")
    public List<SinkView> listSinks() {
        long head = exportService.ledgerHead();
        return exportService.listSinks().stream().map(sink -> view(sink, head)).toList();
    }

    @DeleteMapping("/sinks/{id}")
    @Tag(name = "Audit")
    @Operation(
            summary = "Delete an audit export sink",
            description = "Removes the sink and its delivery channel; no further batches are queued for it.")
    @ApiResponse(responseCode = "204", description = "Sink deleted")
    @ApiResponse(responseCode = "404", description = "Sink not found")
    public ResponseEntity<Void> deleteSink(@PathVariable long id, Authentication authentication) {
        if (!exportService.deleteSink(id)) {
            return ResponseEntity.notFound().build();
        }
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "audit-sink-deleted", null);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sinks/{id}/cursor")
    @Requirements({"GW_0029"})
    @Tag(name = "Audit")
    @Operation(
            summary = "Set an audit export sink's ledger position",
            description = "Replay: setting the position back re-delivers every ledger entry after it on the next"
                    + " export pass. Receivers de-duplicate on the ledger sequence carried by each entry.")
    @ApiResponse(responseCode = "200", description = "Position updated")
    @ApiResponse(responseCode = "404", description = "Sink not found")
    public SinkView setCursor(
            @PathVariable long id, @RequestBody CursorRequest request, Authentication authentication) {
        AuditSink updated = exportService
                .resetCursor(id, request.after())
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sink %d not found".formatted(id)));
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "audit-sink-cursor-reset", null);
        return view(updated, exportService.ledgerHead());
    }

    private SinkView view(AuditSink sink, long ledgerHead) {
        return new SinkView(
                sink.id(),
                sink.name(),
                sink.kind(),
                exportService.sinkUrl(sink),
                sink.cursorPosition(),
                ledgerHead,
                Math.max(ledgerHead - sink.cursorPosition(), 0),
                sink.batchSize(),
                sink.enabled(),
                sink.createdAt());
    }

    /** Fails closed, exactly like marketplace and webhook subscriber registration. */
    private void requireAllowlistedScheme(String url) {
        String scheme = null;
        if (url != null) {
            try {
                scheme = new URI(url).getScheme();
            } catch (URISyntaxException e) {
                scheme = null;
            }
        }
        if (scheme == null || !properties.allowedUrlSchemes().contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "url scheme must be one of %s".formatted(properties.allowedUrlSchemes()));
        }
    }
}
