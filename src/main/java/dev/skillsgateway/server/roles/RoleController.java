package dev.skillsgateway.server.roles;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Role grant management (GW_0071). Admin-only while enforcement is enabled; while disabled the
 * grants are inert data any session may stage before flipping the switch — the flip itself is a
 * configuration decision, strictly more privileged than any API caller.
 */
@RestController
@RequestMapping("/api")
public class RoleController {

    private static final Set<String> ROLES = Set.of(RoleGrant.ADMIN, RoleGrant.APPROVER, RoleGrant.AUDITOR);

    /** Grant administration is not itself tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final RoleService roleService;
    private final RoleGrantRepository roleGrantRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final AdminAuditLogger auditLogger;

    public RoleController(
            RoleService roleService,
            RoleGrantRepository roleGrantRepository,
            MarketplaceRepository marketplaceRepository,
            AdminAuditLogger auditLogger) {
        this.roleService = roleService;
        this.roleGrantRepository = roleGrantRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.auditLogger = auditLogger;
    }

    @Schema(description = "Role grant request")
    public record GrantRequest(
            @Schema(description = "Principal to grant the role to", requiredMode = Schema.RequiredMode.REQUIRED)
            String principal,

            @Schema(
                    description = "The role",
                    allowableValues = {"admin", "approver", "auditor"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String role,

            @Schema(
                    description = "Marketplace to scope an approver grant to; required for approver,"
                            + " forbidden for admin and auditor")
            String marketplace) {}

    @GetMapping("/roles")
    @Requirements({"GW_0071"})
    @Tag(name = "Roles")
    @Operation(
            summary = "List role grants",
            description = "Every current grant. Configuration-bootstrapped admins are not grants and do not"
                    + " appear here; they show up as an effective role on /api/me. Admin-only while role"
                    + " enforcement is enabled.")
    @ApiResponse(responseCode = "200", description = "Current grants")
    @ApiResponse(responseCode = "403", description = "Enforcement is enabled and the caller is not an admin")
    public List<RoleGrant> list(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return roleGrantRepository.list();
    }

    @PostMapping("/roles")
    @Requirements({"GW_0071"})
    @Tag(name = "Roles")
    @Operation(
            summary = "Grant a role",
            description = "Grants admin or auditor globally, or approver scoped to one existing marketplace."
                    + " Every grant lands on the append-only ledger with the acting identity. Admin-only while"
                    + " role enforcement is enabled; while disabled, grants are inert data a deployment stages"
                    + " before enabling enforcement.")
    @ApiResponse(responseCode = "201", description = "Role granted")
    @ApiResponse(responseCode = "403", description = "Enforcement is enabled and the caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Approver grant names a marketplace that does not exist")
    @ApiResponse(responseCode = "409", description = "The identical grant already exists")
    @ApiResponse(
            responseCode = "422",
            description = "Missing principal, unknown role, approver grant without a marketplace, or a"
                    + " global grant with one")
    public ResponseEntity<RoleGrant> grant(@RequestBody GrantRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        if (request.principal() == null || request.principal().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "a principal is required");
        }
        if (request.role() == null || !ROLES.contains(request.role())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "role must be one of %s".formatted(ROLES));
        }
        Long marketplaceId = resolveScope(request);
        RoleGrant grant = roleGrantRepository
                .insert(request.principal(), request.role(), marketplaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "that grant already exists for '%s'".formatted(request.principal())));
        auditLogger.record(
                authentication.getName(),
                grant.marketplace() == null ? NO_MARKETPLACE : grant.marketplace(),
                "role-granted",
                null,
                "principal=%s role=%s".formatted(grant.principal(), grant.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @DeleteMapping("/roles/{id}")
    @Requirements({"GW_0071"})
    @Tag(name = "Roles")
    @Operation(
            summary = "Revoke a role grant",
            description = "Deletes the grant; the ledger keeps the history. Configuration-bootstrapped admins"
                    + " have no grant row, so they cannot be revoked here — by design, they are the escape"
                    + " hatch that survives a bad grant edit. Admin-only while role enforcement is enabled.")
    @ApiResponse(responseCode = "204", description = "Grant revoked")
    @ApiResponse(responseCode = "403", description = "Enforcement is enabled and the caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Grant not found")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication authentication) {
        roleService.requireAdmin(authentication);
        RoleGrant grant = roleGrantRepository
                .findById(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "grant %d not found".formatted(id)));
        roleGrantRepository.delete(id);
        auditLogger.record(
                authentication.getName(),
                grant.marketplace() == null ? NO_MARKETPLACE : grant.marketplace(),
                "role-revoked",
                null,
                "principal=%s role=%s".formatted(grant.principal(), grant.role()));
        return ResponseEntity.noContent().build();
    }

    /** An approver grant is scoped to one existing marketplace; the global roles must not be. */
    @Requirements({"GW_0071"})
    private Long resolveScope(GrantRequest request) {
        boolean approver = RoleGrant.APPROVER.equals(request.role());
        boolean scoped = request.marketplace() != null && !request.marketplace().isBlank();
        if (approver && !scoped) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "an approver grant is scoped to one marketplace");
        }
        if (!approver && scoped) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "a %s grant is global and cannot name a marketplace".formatted(request.role()));
        }
        if (!approver) {
            return null;
        }
        return marketplaceRepository
                .findByName(request.marketplace())
                .map(Marketplace::id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(request.marketplace())));
    }
}
