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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A marketplace's automatic approval (GW_APPROVAL_0044.1, GW_APPROVAL_0044.3, GW_APPROVAL_0044.4).
 * Setting it is administrator-only: automatic approval is the switch that lets content be published
 * with no person, and the role that may hold it is the one that governs the gateway. Reading it is
 * open to any session, as the marketplace's Settings page is (GW_APPROVAL_0044.15), so automatic
 * approval is never invisible to the people whose content it publishes.
 */
@RestController
@RequestMapping("/api/v1")
public class AutoApprovalSettingController {

    private final AutoApprovalSettingService settingService;
    private final RoleService roleService;
    private final AutoApprovalService autoApprovalService;
    private final AutoApprovalWithdrawal withdrawal;

    public AutoApprovalSettingController(
            AutoApprovalSettingService settingService,
            RoleService roleService,
            AutoApprovalService autoApprovalService,
            AutoApprovalWithdrawal withdrawal) {
        this.autoApprovalService = autoApprovalService;
        this.withdrawal = withdrawal;
        this.settingService = settingService;
        this.roleService = roleService;
    }

    @GetMapping("/marketplaces/{name}/auto-approval")
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.15"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "A marketplace's automatic approval",
            description = "Whether it is on, whether it takes effect and what allows it, who set it, why, when it"
                    + " lapses, the active exemption, and the gateway's mode. What allows it is computed at every"
                    + " read. Open to any session.")
    @ApiResponse(responseCode = "200", description = "The automatic approval")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    public AutoApprovalSettingService.SettingView autoApproval(@PathVariable String name) {
        return settingService.view(name);
    }

    @PutMapping("/marketplaces/{name}/auto-approval")
    @Requirements({"GW_APPROVAL_0044.1", "GW_APPROVAL_0044.3", "GW_APPROVAL_0044.4"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Turn a marketplace's automatic approval on or off",
            description = "Off, or on with a reason and an expiry of at most 90 days. A marketplace outside the"
                    + " internal prefixes needs an administrator exemption, given here, with its own reason and"
                    + " expiry; automatic approval never outlives it. Turning it off revokes the exemption. Under"
                    + " four-eyes enforce, a grant by the marketplace's registrant is stored but awaits a different"
                    + " administrator, who confirms it by setting it again. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The automatic approval after the change")
    @ApiResponse(
            responseCode = "400",
            description = "No enabled; on without a reason or expiry, or for longer than allowed; an exemption"
                    + " without either, or for an internal marketplace")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    @ApiResponse(
            responseCode = "409",
            description = "on for a marketplace that is not internal and has no exemption; nextStep says how")
    public AutoApprovalSettingService.SettingView setAutoApproval(
            @PathVariable String name,
            @RequestBody(required = false) AutoApprovalSettingService.SettingRequest request,
            Authentication authentication) {
        roleService.requireAdmin(authentication);
        AutoApprovalSettingService.SettingView view = settingService.set(name, request, authentication.getName());
        autoApprovalService.attemptNewest(name, "auto-approval-set");
        return view;
    }

    @DeleteMapping("/marketplaces/{name}/auto-approval/exemption")
    @Requirements({"GW_APPROVAL_0044.3"})
    @Tag(name = "Marketplaces")
    @Operation(
            summary = "Revoke a marketplace's administrator exemption",
            description = "Ends the active exemption at once. Automatic approval that rested on it no longer takes"
                    + " effect. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The automatic approval after the revocation")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found, or it has no active exemption")
    public AutoApprovalSettingService.SettingView revokeExemption(
            @PathVariable String name, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return settingService.revokeExemption(name, authentication.getName());
    }

    @PostMapping("/auto-approval/withdraw")
    @Requirements({"GW_APPROVAL_0044.14"})
    @Tag(name = "Snapshots")
    @Operation(
            summary = "Withdraw every automatic approval since a time",
            description = "Revokes every live snapshot approved by auto-approver at or after since, newest decision"
                    + " first, each as an ordinary withdrawal with the stated reason, ledgered and announced."
                    + " Snapshots a person approved are untouched. A marketplace whose served snapshot is withdrawn"
                    + " serves what serveAfter says: its newest approved snapshot this request does not withdraw,"
                    + " or nothing. A return that a marketplace cannot make refuses the whole request before"
                    + " anything is withdrawn. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "What was withdrawn, newest decision first")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(
            responseCode = "409",
            description = "A return to the previous approved snapshot was asked for and a marketplace has none;"
                    + " nothing was withdrawn")
    @ApiResponse(responseCode = "422", description = "No since, no reason, or no served-content choice")
    public AutoApprovalWithdrawal.WithdrawResponse withdraw(
            @RequestBody(required = false) AutoApprovalWithdrawal.WithdrawRequest request,
            Authentication authentication) {
        roleService.requireAdmin(authentication);
        return withdrawal.withdraw(request, authentication.getName());
    }
}
