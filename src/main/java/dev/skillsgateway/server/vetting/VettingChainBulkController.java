package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One vetting-chain change addressed to several marketplaces (GW_VETTING_0037), and the only way to
 * remove a per-marketplace override (GW_VETTING_0036) — a clear over a selection of one.
 *
 * <p>Administrator-only, for the reason {@link VetterToggleController} records. An estate-wide
 * change is the sharpest form of the same control, so the boundary is the same one.
 */
@RestController
@RequestMapping("/api")
public class VettingChainBulkController {

    private final VettingChainBulkService bulkService;
    private final RoleService roleService;

    public VettingChainBulkController(VettingChainBulkService bulkService, RoleService roleService) {
        this.bulkService = bulkService;
        this.roleService = roleService;
    }

    @PostMapping("/vetting/chain-settings/bulk")
    @Requirements({"GW_VETTING_0036", "GW_VETTING_0037"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Apply one chain change to several marketplaces",
            description = "Sets the chain mode, sets the vetter order, switches one vetter, or removes"
                    + " per-marketplace overrides, across the named marketplaces, as a single act. The request's"
                    + " shape is validated whole — an unknown action, mode, vetter or override kind is refused"
                    + " with 422 and nothing is stored or recorded. Each marketplace is then attempted"
                    + " independently: the answer is 200 when every one succeeded and 207 when any did not, and"
                    + " 'results' reports the outcome per marketplace either way. Clearing is idempotent — a"
                    + " marketplace with no override of that kind is reported unchanged and writes nothing."
                    + " Every marketplace the request changed receives its own ledger entry carrying the shared"
                    + " reason and the response's correlationId, so the entries read as one act."
                    + " Administrator-only.")
    @ApiResponse(responseCode = "200", description = "Every named marketplace was applied or already in that state")
    @ApiResponse(responseCode = "207", description = "At least one named marketplace was refused; see 'results'")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(
            responseCode = "422",
            description = "Unknown action, mode, vetter or override kind, or an empty selection")
    public ResponseEntity<BulkChainResult> bulk(@RequestBody BulkChainChange request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        BulkChainResult result = bulkService.apply(request, authentication.getName());
        // 207 is the honest code for "some of this did not happen". A client that reads only the
        // status still learns that much; the portal reads `results` and names each refusal.
        return ResponseEntity.status(result.whollySucceeded() ? HttpStatus.OK : HttpStatus.MULTI_STATUS)
                .body(result);
    }
}
