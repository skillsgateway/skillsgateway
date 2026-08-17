package io.github.jimisola.skillsgateway.catalog;

import io.github.jimisola.skillsgateway.admin.AdminAuditLogger;
import io.github.jimisola.skillsgateway.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The catalog's admin surface (GW_0063), behind the OIDC session like every /api endpoint. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;
    private final AdminAuditLogger auditLogger;
    private final RoleService roleService;

    public CatalogController(CatalogService catalogService, AdminAuditLogger auditLogger, RoleService roleService) {
        this.catalogService = catalogService;
        this.auditLogger = auditLogger;
        this.roleService = roleService;
    }

    @GetMapping("/catalog")
    @Requirements({"GW_0063"})
    @Tag(name = "Catalog")
    @Operation(
            summary = "The served catalog revision",
            description = "The catalog commit the facade is serving at /git/{catalog-name}, with the"
                    + " marketplace and upstream commit SHA of every constituent vendored into it.")
    @ApiResponse(responseCode = "200", description = "Served catalog revision and its constituents")
    @ApiResponse(responseCode = "404", description = "Catalog disabled, or not generated yet")
    public CatalogService.CatalogInfo catalog() {
        if (!catalogService.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog is disabled");
        }
        try {
            return catalogService
                    .served()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "catalog not generated yet; POST /api/catalog/rebuild"));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/catalog/rebuild")
    @Requirements({"GW_0062", "GW_0063"})
    @Tag(name = "Catalog")
    @Operation(
            summary = "Rebuild the catalog now",
            description = "Regenerates the catalog from what every marketplace is serving right now."
                    + " Approvals and revocations already do this on their own; this is the on-demand"
                    + " repair path, and it lands on the audit ledger with the acting identity.")
    @ApiResponse(responseCode = "200", description = "Catalog rebuilt; returns the new revision")
    @ApiResponse(responseCode = "404", description = "Catalog disabled")
    public CatalogService.CatalogInfo rebuild(Authentication authentication) {
        roleService.requireAdmin(authentication);
        if (!catalogService.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog is disabled");
        }
        try {
            CatalogService.CatalogInfo info = catalogService.rebuild();
            auditLogger.record(authentication.getName(), catalogService.name(), "catalog-rebuilt", info.sha());
            return info;
        } catch (IOException | GitAPIException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
