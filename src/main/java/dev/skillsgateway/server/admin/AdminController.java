package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.approval.ApprovalService;
import dev.skillsgateway.server.approval.ReleaseAgeGate;
import dev.skillsgateway.server.approval.SnapshotTooYoungException;
import dev.skillsgateway.server.approval.VettingBlockedException;
import dev.skillsgateway.server.ingestion.IngestionException;
import dev.skillsgateway.server.ingestion.IngestionService;
import dev.skillsgateway.server.ingestion.SnapshotContentService;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.Snapshot;
import dev.skillsgateway.server.persistence.SnapshotNotFoundException;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.policy.PolicyDeniedException;
import dev.skillsgateway.server.roles.RoleService;
import dev.skillsgateway.server.vetting.LicenseReportService;
import dev.skillsgateway.server.vetting.WaiverService;
import dev.skillsgateway.server.webhook.WebhookEvent;
import dev.skillsgateway.server.webhook.WebhookService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /** The gateway ingests only the upstream default branch; this is not consumer-selectable. */
    private static final String DEFAULT_BRANCH = "main";

    private final MarketplaceRegistrationService registrationService;
    private final MarketplaceRepository marketplaceRepository;
    private final SnapshotRepository snapshotRepository;
    private final IngestionService ingestionService;
    private final ApprovalService approvalService;
    private final FetchLogRepository fetchLogRepository;
    private final SnapshotContentService snapshotContentService;
    private final LicenseReportService licenseReportService;
    private final AdminAuditLogger auditLogger;
    private final WebhookService webhookService;
    private final WaiverService waiverService;
    private final RoleService roleService;

    public AdminController(
            MarketplaceRegistrationService registrationService,
            MarketplaceRepository marketplaceRepository,
            SnapshotRepository snapshotRepository,
            IngestionService ingestionService,
            ApprovalService approvalService,
            FetchLogRepository fetchLogRepository,
            SnapshotContentService snapshotContentService,
            LicenseReportService licenseReportService,
            AdminAuditLogger auditLogger,
            WebhookService webhookService,
            WaiverService waiverService,
            RoleService roleService) {
        this.registrationService = registrationService;
        this.marketplaceRepository = marketplaceRepository;
        this.snapshotRepository = snapshotRepository;
        this.ingestionService = ingestionService;
        this.approvalService = approvalService;
        this.fetchLogRepository = fetchLogRepository;
        this.snapshotContentService = snapshotContentService;
        this.licenseReportService = licenseReportService;
        this.auditLogger = auditLogger;
        this.webhookService = webhookService;
        this.waiverService = waiverService;
        this.roleService = roleService;
    }

    @Schema(description = "Marketplace registration request")
    public record RegisterMarketplaceRequest(
            @Schema(
                    description = "Gateway-local marketplace name; becomes the facade clone path /git/{name}",
                    example = "corp-marketplace",
                    pattern = "^[a-z0-9][a-z0-9_-]*$")
            String name,

            @Schema(
                    description =
                            "Upstream clone URL; scheme must be on the configured allowlist" + " (default http/https)",
                    example = "https://github.com/acme/skills-marketplace.git")
            String url,

            @Schema(
                    description = "Where the content comes from: 'upstream' (the default) fetches the clone URL;"
                            + " 'hosted' takes no URL and gives the marketplace a gateway-owned origin repository"
                            + " that publishers push to",
                    allowableValues = {"upstream", "hosted"},
                    example = "upstream")
            String origin,

            @Schema(
                    description = "For a hosted marketplace, whether its publisher may rewrite the lineage."
                            + " Defaults to append-only, which refuses a non-fast-forward push.",
                    allowableValues = {"append-only", "allow-rewrite"})
            String pushPolicy,

            @Schema(
                    description = "Must be omitted or equal the upstream default branch; the ingested ref"
                            + " is the gateway's decision, never the consumer's (GW_0017)",
                    example = "main")
            String ref) {}

    @Schema(description = "A registered marketplace with its snapshots and forge metadata")
    public record MarketplaceView(
            @Schema(description = "Marketplace id") long id,

            @Schema(description = "Gateway-local name (also the facade clone path)")
            String name,

            @Schema(description = "Upstream clone URL; null for a gateway-hosted marketplace")
            String url,

            @Schema(description = "Registration time") Instant createdAt,

            @Schema(
                    description = "Where the content comes from",
                    allowableValues = {"upstream", "hosted"})
            String origin,

            @Schema(
                    description = "Whether a hosted marketplace's publisher may rewrite its lineage",
                    allowableValues = {"append-only", "allow-rewrite"})
            String pushPolicy,

            @Schema(description = "Path publishers push a hosted marketplace to; null for an upstream one")
            String publishPath,

            @Schema(description = "Detected forge (github, gitlab, bitbucket, azure-devops, gitea) or null")
            String forge,

            @Schema(description = "Project path on the forge, e.g. acme/skills")
            String forgeProject,

            @Schema(description = "Project description from the forge")
            String description,

            @Schema(description = "Last upstream update as reported by the forge")
            Instant upstreamUpdatedAt,

            @Schema(
                    description = "How upstream content reaches quarantine (GW_0056)",
                    allowableValues = {"on-demand", "scheduled", "webhook"})
            String syncMode,

            @Schema(description = "All snapshots of this marketplace, any state")
            List<Snapshot> snapshots) {}

    @PostMapping("/marketplaces")
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Register a marketplace",
            description = "Registers a git skill marketplace. An upstream marketplace (the default) names a clone"
                    + " URL whose scheme must be on the configured allowlist (default http/https); the ingested"
                    + " ref is set by the gateway (the upstream default branch) and cannot be overridden. A hosted"
                    + " marketplace takes no URL at all and is published to by pushing to /publish/{name}.")
    @ApiResponse(responseCode = "201", description = "Marketplace registered")
    @ApiResponse(responseCode = "400", description = "Disallowed URL scheme, or a ref other than the default branch")
    @ApiResponse(responseCode = "409", description = "A marketplace with that name already exists")
    @ApiResponse(responseCode = "422", description = "Invalid marketplace name")
    public ResponseEntity<Marketplace> registerMarketplace(
            @RequestBody RegisterMarketplaceRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        requireDefaultBranchRef(request.ref());
        Marketplace marketplace = registrationService.register(
                request.name(), request.url(), request.origin(), request.pushPolicy(), authentication.getName());
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

    @GetMapping("/snapshots/{id}/licenses")
    @Requirements({"GW_0095"})
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Snapshot license report",
            description = "The licenses the snapshot declares — detected deterministically from license/copying"
                    + " files and marketplace-manifest metadata over the content pinned to the snapshot's commit"
                    + " SHA — each with its SPDX id (or its unknown state) and its standing under the license"
                    + " allow/ban policy currently configured. Computed from the pinned content and the current"
                    + " configuration: the historical evidence the approval gate read lives in the recorded"
                    + " vetting runs. Complements the snapshot content inventory and the gateway's own SBOM"
                    + " endpoint as the supply-chain read surface.")
    @ApiResponse(responseCode = "200", description = "Detected licenses and their policy evaluation")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public LicenseReportService.LicenseReport snapshotLicenses(@PathVariable long id) {
        return licenseReportService.report(id);
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
            description = "All registered marketplaces with every snapshot and its state"
                    + " (held, approved, rejected, or revoked).")
    public List<MarketplaceView> listMarketplaces() {
        return marketplaceRepository.list().stream()
                .map(marketplace -> new MarketplaceView(
                        marketplace.id(),
                        marketplace.name(),
                        marketplace.url(),
                        marketplace.createdAt(),
                        marketplace.origin(),
                        marketplace.pushPolicy(),
                        marketplace.hosted() ? "/publish/" + marketplace.name() : null,
                        marketplace.forge(),
                        marketplace.forgeProject(),
                        marketplace.description(),
                        marketplace.upstreamUpdatedAt(),
                        marketplace.syncMode(),
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
        roleService.requireApprover(authentication, name);
        Marketplace marketplace = marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
        Snapshot snapshot = ingestionService.ingest(marketplace);
        auditLogger.record(authentication.getName(), marketplace.name(), "snapshot-ingested", snapshot.sha());
        emit(WebhookEvent.SNAPSHOT_INGESTED, marketplace.name(), snapshot, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshot);
    }

    @PostMapping("/snapshots/{id}/approve")
    @Requirements({"GW_0041", "GW_0050"})
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Approve a held or revoked snapshot",
            description = "Publishes the snapshot to the git facade and records the reviewer identity and"
                    + " timestamp. Takes no request body. Held snapshots and snapshots that re-vetting revoked"
                    + " can be approved; a revoked one is re-published only by this fresh decision, behind the"
                    + " same gate, which means the violation that revoked it must have been waived or fixed"
                    + " first. A snapshot whose"
                    + " effective vetting outcome is blocked — its chain run objects and at least one blocking"
                    + " finding is not covered by an active waiver, including a snapshot with no chain run at"
                    + " all — is refused, and the problem document names both the blocking connectors and the"
                    + " uncovered findings. Record a scoped, expiring waiver for each of those findings and"
                    + " approve again; every waiver that let the approval through is written to the ledger.")
    @ApiResponse(responseCode = "200", description = "Snapshot approved and now served")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(
            responseCode = "409",
            description = "Snapshot is neither held nor revoked, its effective vetting outcome is blocked, or it"
                    + " has not yet reached the configured minimum release age")
    public Snapshot approve(@PathVariable long id, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        ApprovalService.Approved approved = approvalService.approve(id, authentication.getName());
        Snapshot snapshot = approved.snapshot();
        String marketplace = marketplaceName(snapshot.marketplaceId());
        waiverService.recordUse(marketplace, snapshot.sha(), authentication.getName(), approved.waiversApplied());
        // The age at approval is on the decision's own ledger entry (GW_0073): what the cooling-off
        // window was worth for this snapshot is only reconstructible if the entry says how long the
        // commit had been sitting in quarantine when someone adopted it.
        auditLogger.record(
                authentication.getName(),
                marketplace,
                "snapshot-approved",
                snapshot.sha(),
                "ingestion-age=" + ReleaseAgeGate.format(approved.ingestionAge()));
        emit(
                WebhookEvent.SNAPSHOT_APPROVED,
                marketplaceName(snapshot.marketplaceId()),
                snapshot,
                authentication.getName());
        return snapshot;
    }

    @PostMapping("/snapshots/{id}/reject")
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Reject a held or revoked snapshot",
            description = "Marks the snapshot rejected with the reviewer identity and timestamp; its content is"
                    + " never served again. This is the terminal answer to a re-vetting revocation the reviewer"
                    + " does not intend to waive.")
    @ApiResponse(responseCode = "200", description = "Snapshot rejected")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    @ApiResponse(responseCode = "409", description = "Snapshot is neither held nor revoked")
    public Snapshot reject(@PathVariable long id, Authentication authentication) {
        roleService.requireApproverOfSnapshot(authentication, id);
        Snapshot snapshot = approvalService.reject(id, authentication.getName());
        auditLogger.record(
                authentication.getName(),
                marketplaceName(snapshot.marketplaceId()),
                "snapshot-rejected",
                snapshot.sha());
        emit(
                WebhookEvent.SNAPSHOT_REJECTED,
                marketplaceName(snapshot.marketplaceId()),
                snapshot,
                authentication.getName());
        return snapshot;
    }

    /** Enqueue-only: the admin response never waits on, nor fails because of, a receiver. */
    private void emit(String event, String marketplace, Snapshot snapshot, String actor) {
        webhookService.emit(event, marketplace, snapshot.id(), snapshot.sha(), snapshot.state(), actor);
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

    @GetMapping("/snapshots/{id}/release-age")
    @Requirements({"GW_0073"})
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Minimum release age eligibility",
            description = "Whether the snapshot has cleared the configured cooling-off window and may be"
                    + " approved, and when it will if it has not. The age is measured from the instant the"
                    + " gateway first ingested the commit — never from the commit's own timestamp, which whoever"
                    + " made the commit controls — and re-ingesting the same commit does not reset it. Computed"
                    + " on every request, so a snapshot becomes eligible with nothing having had to run in the"
                    + " background. With the gate off (the default) every snapshot reports eligible.")
    @ApiResponse(responseCode = "200", description = "Eligibility and the time remaining")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public ReleaseAgeGate.Eligibility releaseAge(@PathVariable long id) {
        return approvalService
                .releaseAge(id)
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
    public List<Map<String, Object>> audit(Authentication authentication) {
        roleService.requireAuditor(authentication);
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

    /**
     * Fail-closed approval gate (GW_0041). The response names the connectors that blocked and,
     * beside them, every blocking finding no active waiver covers — which is exactly the set of
     * waivers the reviewer must record for this approval to succeed.
     */
    @ExceptionHandler(VettingBlockedException.class)
    public ProblemDetail vettingBlocked(VettingBlockedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Vetting chain blocked this snapshot");
        problem.setProperty("blockingConnectors", e.blockingConnectors());
        problem.setProperty("uncoveredFindings", e.uncoveredFindings());
        return problem;
    }

    /**
     * Fail-closed policy gate (GW_0090). The response names every deciding rule with its outcome
     * — {@code matched}, or {@code error: ...} for a rule that could not evaluate — because the
     * remedy differs: a matched rule means the content is prohibited; an erroring rule means an
     * admin fixes or disables the rule.
     */
    @ExceptionHandler(PolicyDeniedException.class)
    public ProblemDetail policyDenied(PolicyDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Policy rules denied this snapshot");
        problem.setProperty("denials", e.denials());
        return problem;
    }

    /**
     * The cooling-off window (GW_0073). The response says which setting imposed the wait and how
     * much of it is left, so the reader can tell the two possible answers apart: wait, or change
     * the configuration — there is deliberately no per-approval override to reach for.
     */
    @ExceptionHandler(SnapshotTooYoungException.class)
    public ProblemDetail snapshotTooYoung(SnapshotTooYoungException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Snapshot has not reached the minimum release age");
        problem.setProperty("configKey", ReleaseAgeGate.CONFIG_KEY);
        problem.setProperty("minimumReleaseAge", e.minimum().toString());
        problem.setProperty("eligibility", e.eligibility());
        return problem;
    }
}
