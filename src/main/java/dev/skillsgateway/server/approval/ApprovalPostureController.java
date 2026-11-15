package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.roles.RoleService;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A marketplace's approval posture (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3, GW_APPROVAL_0044.4).
 * Administrator-only, reads included: a posture is the switch that lets content be published with
 * no person, and the role that may hold it is the one that governs the gateway.
 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalPostureController {

    private final ApprovalPostureService postureService;
    private final RoleService roleService;

    public ApprovalPostureController(ApprovalPostureService postureService, RoleService roleService) {
        this.postureService = postureService;
        this.roleService = roleService;
    }

    @GetMapping("/marketplaces/{name}/approval-posture")
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "A marketplace's approval posture",
            description = "The stored posture, whether it takes effect and on what basis, who set it, why, when it"
                    + " lapses, and the active override. The basis is computed at every read. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The posture")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    public ApprovalPostureService.PostureView posture(@PathVariable String name, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return postureService.view(name);
    }

    @PutMapping("/marketplaces/{name}/approval-posture")
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Set a marketplace's approval posture",
            description = "manual, or auto with a reason and an expiry of at most 90 days. A marketplace outside"
                    + " the internal prefixes needs an override, given here, with its own reason and expiry; the"
                    + " posture never outlives it. Setting manual revokes the override. Under four-eyes enforce, a"
                    + " grant by the marketplace's registrant is stored but awaits a different administrator,"
                    + " who confirms it by setting it again. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The posture after the change")
    @ApiResponse(
            responseCode = "400",
            description = "No posture; auto without a reason or expiry, or for longer than allowed; an override"
                    + " without either, or for an internal marketplace")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    @ApiResponse(
            responseCode = "409",
            description = "auto for a marketplace with no internal basis and no override; nextStep says how")
    public ApprovalPostureService.PostureView setPosture(
            @PathVariable String name,
            @RequestBody(required = false) ApprovalPostureService.PostureRequest request,
            Authentication authentication) {
        roleService.requireAdmin(authentication);
        return postureService.set(name, request, authentication.getName());
    }

    @DeleteMapping("/marketplaces/{name}/approval-posture/override")
    @Requirements({"GW_APPROVAL_0044.3"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Revoke a marketplace's override",
            description = "Ends the active override at once. A posture that rested on it no longer takes effect."
                    + " Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The posture after the revocation")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found, or it has no active override")
    public ApprovalPostureService.PostureView revokeOverride(@PathVariable String name, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return postureService.revokeOverride(name, authentication.getName());
    }
}
