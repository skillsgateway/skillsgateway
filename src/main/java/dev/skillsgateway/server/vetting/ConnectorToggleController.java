package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The administrative connector on/off surface (GW_0149). Both endpoints are administrator-only:
 * the switch that governs the vetting chain, and even the visibility of its current settings, are
 * not something a marketplace-scoped approver may reach — that would let the owner of content turn
 * off the control that governs it.
 */
@RestController
@RequestMapping("/api")
public class ConnectorToggleController {

    private final ConnectorToggleService toggleService;
    private final RoleService roleService;

    public ConnectorToggleController(ConnectorToggleService toggleService, RoleService roleService) {
        this.toggleService = toggleService;
        this.roleService = roleService;
    }

    @Schema(description = "Enable or disable a built-in vetting connector, globally or for one marketplace")
    public record ToggleRequest(
            @Schema(description = "Whether the connector should run under this setting")
            Boolean enabled,

            @Schema(description = "Marketplace to scope the setting to; omit for the global setting")
            String marketplace,

            @Schema(description = "Optional note recorded with the change and on the audit ledger")
            String reason) {}

    @GetMapping("/vetting/connector-toggles")
    @Requirements({"GW_0149"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "List connector enable/disable settings",
            description = "Every administrative enable/disable setting for the built-in vetting connectors — the"
                    + " global settings and the per-marketplace overrides. Administrator-only: the switch that"
                    + " governs the vetting chain is not shown to marketplace-scoped approvers.")
    @ApiResponse(responseCode = "200", description = "The connector settings")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    public List<ConnectorToggle> toggles(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return toggleService.list();
    }

    @PutMapping("/vetting/connectors/{name}/toggle")
    @Requirements({"GW_0149"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Enable or disable a built-in connector",
            description = "Switches a specific built-in vetting connector on or off, globally or for one named"
                    + " marketplace, and records the change on the audit ledger. A per-marketplace setting overrides"
                    + " the global one; the absence of any setting means the connector runs. A disabled connector is"
                    + " not run at ingestion or re-vetting but is recorded as a distinct disabled verdict on the"
                    + " chain run, and disabling every connector leaves a run blocked rather than clear."
                    + " Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The setting after the change")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    @ApiResponse(responseCode = "422", description = "Unknown connector, or the enabled field was omitted")
    public ConnectorToggle toggle(
            @PathVariable String name, @RequestBody ToggleRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "the 'enabled' field is required (true or false)");
        }
        return toggleService.set(
                name, request.marketplace(), request.enabled(), request.reason(), authentication.getName());
    }
}
