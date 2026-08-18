package dev.skillsgateway.server.adoption;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The adoption reporting surface (GW_0075, GW_0076): read-only, derived entirely from the fetch
 * ledger and the served tips. Both reads enumerate identities off the ledger, so they are gated
 * exactly like the ledger itself (auditor or admin once role enforcement is on).
 */
@RestController
@RequestMapping("/api/adoption")
public class AdoptionController {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 365;

    private final AdoptionService adoptionService;
    private final RoleService roleService;

    public AdoptionController(AdoptionService adoptionService, RoleService roleService) {
        this.adoptionService = adoptionService;
        this.roleService = roleService;
    }

    @GetMapping
    @Requirements({"GW_0075"})
    @Tag(name = "Adoption")
    @Operation(
            summary = "Adoption report over the fetch ledger",
            description = "Per marketplace: content-transferring fetches in the window, distinct fetching"
                    + " identities, the most recent fetch, and the per-snapshot-SHA breakdown with each SHA"
                    + " marked current against the served tip. Attribution is by authenticated identity as"
                    + " the ledger records it — the gateway has no team concept. Out-of-range windows are"
                    + " clamped to 1..365 days.")
    @ApiResponse(responseCode = "200", description = "The adoption report, one entry per fetched marketplace")
    public List<AdoptionService.MarketplaceAdoption> adoption(
            @Parameter(description = "Report window in days, default 30, clamped to 1..365")
                    @RequestParam(required = false, defaultValue = "" + DEFAULT_WINDOW_DAYS)
                    int days,
            Authentication authentication) {
        roleService.requireAuditor(authentication);
        return adoptionService.adoption(Math.clamp(days, 1, MAX_WINDOW_DAYS));
    }

    @GetMapping("/staleness")
    @Requirements({"GW_0076"})
    @Tag(name = "Adoption")
    @Operation(
            summary = "Identities not on the served tip",
            description = "Every identity whose most recent content-transferring fetch of a marketplace"
                    + " received a SHA that is not the currently served tip. A null servedSha means the"
                    + " marketplace stopped serving entirely (revoked or unpublished) — the identity holds"
                    + " retracted content. The report states facts, not verdicts: an identity may be pinned"
                    + " to an old SHA on purpose.")
    @ApiResponse(responseCode = "200", description = "The stale identities, window-free")
    public List<AdoptionService.StaleIdentity> staleness(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return adoptionService.staleness();
    }
}
