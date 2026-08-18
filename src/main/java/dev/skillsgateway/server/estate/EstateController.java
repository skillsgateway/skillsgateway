package dev.skillsgateway.server.estate;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The declarative estate's API surface (GW_0087): the last report, and the on-demand trigger. */
@RestController
@RequestMapping("/api/estate")
public class EstateController {

    /** Estate administration is not tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final EstateReconciler reconciler;
    private final SkillsGatewayProperties properties;
    private final RoleService roleService;
    private final AdminAuditLogger auditLogger;

    public EstateController(
            EstateReconciler reconciler,
            SkillsGatewayProperties properties,
            RoleService roleService,
            AdminAuditLogger auditLogger) {
        this.reconciler = reconciler;
        this.properties = properties;
        this.roleService = roleService;
        this.auditLogger = auditLogger;
    }

    @GetMapping
    @Requirements({"GW_0087"})
    @Tag(name = "Estate")
    @Operation(
            summary = "Last estate reconciliation report",
            description = "The most recent reconciliation run — per declared entry, what was created, updated,"
                    + " unchanged, or failed and why. The report is held in memory; after a restart the startup"
                    + " run repopulates it. Secret values never appear. Auditor or admin while role enforcement"
                    + " is enabled, because failure reasons expose operator infrastructure.")
    @ApiResponse(responseCode = "200", description = "The last reconciliation report")
    @ApiResponse(responseCode = "404", description = "No reconciliation has run")
    public EstateReconciliation lastRun(Authentication authentication) {
        roleService.requireAuditor(authentication);
        return reconciler
                .lastRun()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no reconciliation has run"));
    }

    @PostMapping("/reconcile")
    @Requirements({"GW_0083", "GW_0087"})
    @Tag(name = "Estate")
    @Operation(
            summary = "Reconcile the declared estate now",
            description = "Runs the same additive, idempotent reconciliation as startup against the current"
                    + " declaration and returns its report. A converged estate reconciles with zero writes and"
                    + " zero ledger entries; the trigger itself is an administrative action and is always"
                    + " recorded with the acting identity. Admin-only while role enforcement is enabled.")
    @ApiResponse(responseCode = "200", description = "The run's report")
    @ApiResponse(responseCode = "403", description = "Enforcement is enabled and the caller is not an admin")
    public EstateReconciliation reconcile(Authentication authentication) {
        roleService.requireAdmin(authentication);
        auditLogger.record(authentication.getName(), NO_MARKETPLACE, "estate-reconcile-triggered", null);
        return reconciler.reconcile(properties.estate(), "api");
    }
}
