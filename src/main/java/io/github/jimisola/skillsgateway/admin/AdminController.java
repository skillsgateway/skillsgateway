package io.github.jimisola.skillsgateway.admin;

import io.github.jimisola.skillsgateway.approval.ApprovalService;
import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
import io.github.jimisola.skillsgateway.ingestion.ForgeMetadataService;
import io.github.jimisola.skillsgateway.ingestion.IngestionException;
import io.github.jimisola.skillsgateway.ingestion.IngestionService;
import io.github.jimisola.skillsgateway.ingestion.SnapshotContentService;
import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.jimisola.skillsgateway.persistence.Snapshot;
import io.github.jimisola.skillsgateway.persistence.SnapshotNotFoundException;
import io.github.jimisola.skillsgateway.persistence.SnapshotRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Pattern MARKETPLACE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    /** The gateway ingests only the upstream default branch; this is not consumer-selectable. */
    private static final String DEFAULT_BRANCH = "main";

    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final IngestionService ingestionService;
    private final ApprovalService approvalService;
    private final FetchLogRepository fetchLogRepository;
    private final SkillsGatewayProperties properties;
    private final ForgeMetadataService forgeMetadataService;
    private final SnapshotContentService snapshotContentService;
    private final AdminAuditLogger auditLogger;

    public AdminController(
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            IngestionService ingestionService,
            ApprovalService approvalService,
            FetchLogRepository fetchLogRepository,
            SkillsGatewayProperties properties,
            ForgeMetadataService forgeMetadataService,
            SnapshotContentService snapshotContentService,
            AdminAuditLogger auditLogger) {
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.ingestionService = ingestionService;
        this.approvalService = approvalService;
        this.fetchLogRepository = fetchLogRepository;
        this.properties = properties;
        this.forgeMetadataService = forgeMetadataService;
        this.snapshotContentService = snapshotContentService;
        this.auditLogger = auditLogger;
    }

    public record RegisterMarketplaceRequest(String name, String url, String ref) {}

    public record MarketplaceView(
            long id,
            String name,
            String url,
            Instant createdAt,
            String forge,
            String forgeProject,
            String description,
            Instant upstreamUpdatedAt,
            List<Snapshot> snapshots) {}

    @PostMapping("/marketplaces")
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Register a marketplace",
            description = "Registers an upstream git skill marketplace by clone URL. The URL scheme must be on the"
                    + " configured allowlist (default http/https). The ingested ref is set by the gateway (the"
                    + " upstream default branch) and cannot be overridden; a request supplying any other ref is"
                    + " rejected.")
    @ApiResponse(responseCode = "201", description = "Marketplace registered")
    @ApiResponse(responseCode = "400", description = "Disallowed URL scheme, or a ref other than the default branch")
    @ApiResponse(responseCode = "409", description = "A marketplace with that name already exists")
    @ApiResponse(responseCode = "422", description = "Invalid marketplace name")
    public ResponseEntity<Marketplace> registerMarketplace(
            @RequestBody RegisterMarketplaceRequest request, Authentication authentication) {
        if (request.name() == null || !MARKETPLACE_NAME.matcher(request.name()).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "name must match " + MARKETPLACE_NAME.pattern());
        }
        requireAllowlistedScheme(request.url());
        requireDefaultBranchRef(request.ref());
        if (marketplaceRepository.findByName(request.name()).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "marketplace '%s' already exists".formatted(request.name()));
        }
        Marketplace marketplace = marketplaceRepository.register(
                request.name(),
                request.url(),
                forgeMetadataService.resolve(request.url()).orElse(null));
        auditLogger.record(authentication.getName(), marketplace.name(), "marketplace-registered", null);
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplace);
    }

    @GetMapping("/snapshots/{id}/content")
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Snapshot content inventory",
            description = "The plugins declared by the snapshot's marketplace manifest and the skills found under"
                    + " each plugin's source tree — the basis for future per-plugin/per-skill limiting.")
    @ApiResponse(responseCode = "200", description = "Plugins and skills in the snapshot")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public SnapshotContentService.SnapshotContent snapshotContent(@PathVariable long id) {
        return snapshotContentService.content(id);
    }

    /** Fails closed: scheme-less and unparseable URLs are rejected along with non-allowlisted schemes. */
    @Requirements({"GW_0016"})
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

    /** The ingested ref is the gateway's decision (upstream default branch), never the consumer's. */
    @Requirements({"GW_0017"})
    private void requireDefaultBranchRef(String ref) {
        if (ref != null && !DEFAULT_BRANCH.equals(ref)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ref is set by the gateway (upstream default branch '%s') and cannot be overridden"
                            .formatted(DEFAULT_BRANCH));
        }
    }

    @GetMapping("/marketplaces")
    @Requirements({"GW_0010"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "List marketplaces and their snapshots",
            description =
                    "All registered marketplaces with every snapshot and its state" + " (held, approved, or rejected).")
    public List<MarketplaceView> listMarketplaces() {
        return marketplaceRepository.list().stream()
                .map(marketplace -> new MarketplaceView(
                        marketplace.id(),
                        marketplace.name(),
                        marketplace.url(),
                        marketplace.createdAt(),
                        marketplace.forge(),
                        marketplace.forgeProject(),
                        marketplace.description(),
                        marketplace.upstreamUpdatedAt(),
                        snapshotRepository.listByMarketplace(marketplace.id())))
                .toList();
    }

    @PostMapping("/marketplaces/{name}/ingest")
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Ingest the upstream default branch",
            description = "Fetches the marketplace's upstream default branch into quarantine and records an"
                    + " immutable snapshot pinned to the upstream commit SHA. The snapshot is held until a reviewer"
                    + " approves it; manifests declaring non-local plugin sources are rejected.")
    @ApiResponse(responseCode = "201", description = "Snapshot recorded (held, or rejected on policy violation)")
    @ApiResponse(responseCode = "404", description = "Marketplace not found")
    @ApiResponse(responseCode = "502", description = "Upstream fetch failed")
    public ResponseEntity<Snapshot> ingest(@PathVariable String name, Authentication authentication) {
        Marketplace marketplace = marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
        Snapshot snapshot = ingestionService.ingest(marketplace);
        auditLogger.record(authentication.getName(), marketplace.name(), "snapshot-ingested", snapshot.sha());
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }

    @PostMapping("/snapshots/{id}/approve")
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Approve a held snapshot",
            description = "Publishes the snapshot to the git facade and records the reviewer identity and"
                    + " timestamp. Only held snapshots can be approved.")
    @ApiResponse(responseCode = "200", description = "Snapshot approved and now served")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "Snapshot is not in the held state")
    public Snapshot approve(@PathVariable long id, Authentication authentication) {
        Snapshot snapshot = approvalService.approve(id, authentication.getName());
        auditLogger.record(
                authentication.getName(),
                marketplaceName(snapshot.marketplaceId()),
                "snapshot-approved",
                snapshot.sha());
        return snapshot;
    }

    @PostMapping("/snapshots/{id}/reject")
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Reject a held snapshot",
            description = "Marks the snapshot rejected with the reviewer identity and timestamp;"
                    + " its content is never served.")
    @ApiResponse(responseCode = "200", description = "Snapshot rejected")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "Snapshot is not in the held state")
    public Snapshot reject(@PathVariable long id, Authentication authentication) {
        Snapshot snapshot = approvalService.reject(id, authentication.getName());
        auditLogger.record(
                authentication.getName(),
                marketplaceName(snapshot.marketplaceId()),
                "snapshot-rejected",
                snapshot.sha());
        return snapshot;
    }

    private String marketplaceName(long marketplaceId) {
        return marketplaceRepository
                .findById(marketplaceId)
                .map(Marketplace::name)
                .orElse("unknown");
    }

    @GetMapping("/snapshots/{id}/provenance")
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Snapshot provenance",
            description = "What was served, from where, and who approved it: upstream URL, upstream commit SHA,"
                    + " state, reviewer, and timestamps.")
    @ApiResponse(responseCode = "200", description = "Provenance record")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public ApprovalService.Provenance provenance(@PathVariable long id) {
        return approvalService
                .provenance(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot %d not found".formatted(id)));
    }

    @GetMapping("/audit")
    @Tag(name = "Audit")
    @Operation(
            summary = "Fetch audit ledger",
            description = "Append-only record of every git facade fetch: client source address, authenticated"
                    + " identity, repository, ref, commit SHA, and timestamp. Portal actions are not in this"
                    + " ledger; approvals live in snapshot provenance.")
    public List<Map<String, Object>> audit() {
        return fetchLogRepository.list();
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ProblemDetail snapshotNotFound(SnapshotNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail invalidTransition(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IngestionException.class)
    public ProblemDetail ingestionFailed(IngestionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
