package io.github.jimisola.skillsgateway.vetting;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.jimisola.skillsgateway.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Waiver management (GW_0044, GW_0046, GW_0047): record an accepted risk against a finding, list
 * what a marketplace has accepted, and withdraw one.
 */
@RestController
@RequestMapping("/api")
public class WaiverController {

    private final WaiverService waiverService;
    private final MarketplaceRepository marketplaceRepository;

    public WaiverController(WaiverService waiverService, MarketplaceRepository marketplaceRepository) {
        this.waiverService = waiverService;
        this.marketplaceRepository = marketplaceRepository;
    }

    @Schema(description = "A waiver as returned by the API, with its activity resolved as of now")
    public record WaiverView(
            @Schema(description = "Waiver id") long id,

            @Schema(description = "Marketplace the waiver belongs to")
            String marketplace,

            @Schema(description = "Finding rule identifier being accepted", example = "aws-access-key-id")
            String ruleId,

            @Schema(description = "How the scope value is matched")
            WaiverScope scope,

            @Schema(description = "A commit SHA for snapshot scope, a repository-relative path for path scope")
            String scopeValue,

            @Schema(description = "Why the risk is accepted")
            String justification,

            @Schema(description = "Identity that accepted the risk")
            String approvedBy,

            @Schema(description = "When the waiver was created")
            Instant createdAt,

            @Schema(description = "When the acceptance lapses; never null")
            Instant expiresAt,

            @Schema(description = "When the waiver was revoked, or null")
            Instant revokedAt,

            @Schema(description = "Identity that revoked it, or null")
            String revokedBy,

            @Schema(description = "Whether the waiver suppresses anything right now")
            boolean active) {

        public static WaiverView of(Waiver waiver) {
            return new WaiverView(
                    waiver.id(),
                    waiver.marketplace(),
                    waiver.ruleId(),
                    waiver.scope(),
                    waiver.scopeValue(),
                    waiver.justification(),
                    waiver.approvedBy(),
                    waiver.createdAt(),
                    waiver.expiresAt(),
                    waiver.revokedAt(),
                    waiver.revokedBy(),
                    waiver.active(Instant.now()));
        }
    }

    @Schema(description = "Request to accept one finding rule on a snapshot's marketplace, until an expiry")
    public record WaiverRequest(
            @Schema(
                    description = "Finding rule identifier to accept",
                    example = "aws-access-key-id",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String ruleId,

            @Schema(
                    description = "SNAPSHOT pins the waiver to this snapshot's commit SHA; PATH applies it to a"
                            + " path in the marketplace and survives re-ingestion",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            WaiverScope scope,

            @Schema(
                    description = "Repository-relative path for PATH scope; ignored for SNAPSHOT scope, which"
                            + " always takes the snapshot's own SHA",
                    example = "plugins/hello")
            String path,

            @Schema(
                    description = "Why this risk is accepted; recorded and shown to the next reviewer",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String justification,

            @Schema(
                    description = "When the acceptance lapses. Required and must be in the future: there are no"
                            + " unlimited waivers.",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "2026-12-31T00:00:00Z")
            Instant expiresAt) {}

    @PostMapping("/snapshots/{id}/waivers")
    @Requirements({"GW_0044"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Accept a vetting finding on this snapshot's marketplace",
            description = "Records a scoped, expiring waiver for one finding rule. The marketplace — and, for"
                    + " SNAPSHOT scope, the commit SHA — are taken from the snapshot, so a waiver cannot be"
                    + " mis-scoped to content it does not belong to. A justification, the acting identity and a"
                    + " future expiry are all mandatory; an unlimited waiver cannot be expressed. While an active"
                    + " waiver covers a finding, that finding no longer contributes to the snapshot's effective"
                    + " vetting outcome.")
    @ApiResponse(responseCode = "201", description = "Waiver recorded")
    @ApiResponse(
            responseCode = "400",
            description = "Missing justification or expiry, an expiry in the past, or an unusable scope")
    @ApiResponse(responseCode = "404", description = "Snapshot not found")
    public ResponseEntity<WaiverView> create(
            @PathVariable long id, @RequestBody WaiverRequest request, Authentication authentication) {
        if (request == null) {
            throw new WaiverValidationException("a waiver request body is required");
        }
        Waiver waiver = waiverService.create(
                id,
                request.ruleId(),
                request.scope(),
                request.path(),
                request.justification(),
                request.expiresAt(),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(WaiverView.of(waiver));
    }

    @GetMapping("/marketplaces/{name}/waivers")
    @Requirements({"GW_0047"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "A marketplace's vetting waivers",
            description = "Every waiver recorded for the marketplace, newest first, active and lapsed alike."
                    + " A lapsed or revoked waiver is kept and returned with active=false: the record of what was"
                    + " once accepted, by whom and until when is part of the audit trail.")
    @ApiResponse(responseCode = "200", description = "The marketplace's waivers")
    @ApiResponse(responseCode = "404", description = "Marketplace not found")
    public List<WaiverView> list(@PathVariable String name) {
        Marketplace marketplace = marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
        return waiverService.byMarketplace(marketplace.id()).stream()
                .map(WaiverView::of)
                .toList();
    }

    @DeleteMapping("/waivers/{id}")
    @Requirements({"GW_0046"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Withdraw a waiver",
            description = "Revokes the waiver. It stops suppressing its finding immediately — the effective"
                    + " vetting outcome is recomputed on every read — so a snapshot that was cleared only by this"
                    + " waiver becomes blocked again. The row is kept, with its revoker and time.")
    @ApiResponse(responseCode = "200", description = "Waiver revoked")
    @ApiResponse(responseCode = "404", description = "Waiver not found, or already revoked")
    public WaiverView revoke(@PathVariable long id, Authentication authentication) {
        return waiverService
                .revoke(id, authentication.getName())
                .map(WaiverView::of)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "waiver %d not found, or already revoked".formatted(id)));
    }

    @ExceptionHandler(WaiverValidationException.class)
    public ProblemDetail invalidWaiver(WaiverValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Waiver rejected");
        return problem;
    }
}
