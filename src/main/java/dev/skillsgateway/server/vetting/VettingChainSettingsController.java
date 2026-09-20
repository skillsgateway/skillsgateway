package dev.skillsgateway.server.vetting;

import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
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
 * The administrative surface for how far the vetting chain goes and the order it goes in
 * (GW_VETTING_0032.4, GW_VETTING_0033.1, GW_VETTING_0033.2).
 *
 * <p>Every endpoint here is administrator-only, for the reason {@link VetterToggleController}
 * records: these settings decide how much evidence stands behind every approval in a marketplace,
 * and a marketplace-scoped approver is frequently the owner of the content the chain governs.
 */
@RestController
@RequestMapping("/api/v1")
public class VettingChainSettingsController {

    private final VettingChainSettingsService settingsService;
    private final RoleService roleService;
    private final MarketplaceRepository marketplaceRepository;

    public VettingChainSettingsController(
            VettingChainSettingsService settingsService,
            RoleService roleService,
            MarketplaceRepository marketplaceRepository) {
        this.settingsService = settingsService;
        this.roleService = roleService;
        this.marketplaceRepository = marketplaceRepository;
    }

    @Schema(description = "Set how far the vetting chain runs, globally or for one marketplace")
    public record ChainModeRequest(
            @Schema(
                    description = "How far the chain should run under this setting",
                    allowableValues = {"run-all", "stop-after-fail"})
            String mode,

            @Schema(description = "Marketplace to scope the setting to; omit for the global setting")
            String marketplace,

            @Schema(description = "Optional note recorded with the change and on the audit ledger")
            String reason) {}

    @Schema(description = "Set the order the vetters run in, globally or for one marketplace")
    public record ChainOrderRequest(
            @Schema(description = "Vetter names, in the order they should run")
            List<String> vetters,

            @Schema(description = "Marketplace to scope the setting to; omit for the global setting")
            String marketplace,

            @Schema(description = "Optional note recorded with the change and on the audit ledger")
            String reason) {}

    @Schema(description = "Every administrative chain mode and vetter order setting")
    public record ChainSettings(
            @Schema(description = "The chain mode settings") List<ChainModeSetting> modes,

            @Schema(description = "The vetter order settings")
            List<ChainOrderSetting> orders) {}

    @GetMapping("/vetting/chain-settings")
    @Requirements({"GW_VETTING_0032.4", "GW_VETTING_0033.1"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "List chain mode and vetter order settings",
            description = "Every administrative chain-mode and vetter-order setting — the global settings and"
                    + " the per-marketplace overrides. Administrator-only: the settings that decide how much"
                    + " evidence stands behind an approval are not shown to marketplace-scoped approvers.")
    @ApiResponse(responseCode = "200", description = "The chain settings")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    public ChainSettings settings(Authentication authentication) {
        roleService.requireAdmin(authentication);
        return new ChainSettings(settingsService.modes(), settingsService.orders());
    }

    @GetMapping("/vetting/global-chain-settings")
    @Requirements({"GW_VETTING_0035"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "The chain mode and vetter order a marketplace with no override runs",
            description = "How far the chain runs and the order it runs in for a marketplace that overrides"
                    + " neither, with which setting decided each. The same shape as the per-marketplace read one"
                    + " resolution level up, so the estate-wide surface and the per-marketplace one cannot"
                    + " disagree. The source is never MARKETPLACE here — only GLOBAL or DEFAULT."
                    + " Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The default chain settings")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    public ChainSettingsView globalChainSettings(Authentication authentication) {
        roleService.requireAdmin(authentication);
        VettingChainSettingsService.ModeResolution mode = settingsService.resolveGlobalMode();
        VettingChainSettingsService.OrderResolution order = settingsService.resolveGlobalOrder();
        ChainModeSetting modeSetting = mode.setting();
        ChainOrderSetting orderSetting = order.setting();
        return new ChainSettingsView(
                mode.mode(),
                mode.source(),
                modeSetting == null ? null : modeSetting.reason(),
                modeSetting == null ? null : modeSetting.updatedBy(),
                modeSetting == null ? null : modeSetting.updatedAt(),
                settingsService.globalOrderedVetters().stream()
                        .map(Vetter::name)
                        .toList(),
                order.override(),
                order.source(),
                orderSetting == null ? null : orderSetting.reason(),
                orderSetting == null ? null : orderSetting.updatedBy(),
                orderSetting == null ? null : orderSetting.updatedAt());
    }

    @GetMapping("/marketplaces/{name}/vetting-chain-settings")
    @Requirements({"GW_VETTING_0032.1", "GW_VETTING_0033.2"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "A marketplace's effective chain mode and vetter order",
            description = "How far the chain runs for this marketplace and the order it runs in, with which"
                    + " setting decided each — the marketplace-scoped setting, the global setting, or the absence"
                    + " of any setting. The resolution is the chain's own, so it cannot disagree with what"
                    + " actually runs. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The effective chain settings")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    public ChainSettingsView chainSettings(@PathVariable String name, Authentication authentication) {
        roleService.requireAdmin(authentication);
        Marketplace marketplace = marketplace(name);
        VettingChainSettingsService.ModeResolution mode = settingsService.resolveMode(marketplace.id());
        VettingChainSettingsService.OrderResolution order = settingsService.resolveOrder(marketplace.id());
        ChainModeSetting modeSetting = mode.setting();
        ChainOrderSetting orderSetting = order.setting();
        return new ChainSettingsView(
                mode.mode(),
                mode.source(),
                modeSetting == null ? null : modeSetting.reason(),
                modeSetting == null ? null : modeSetting.updatedBy(),
                modeSetting == null ? null : modeSetting.updatedAt(),
                settingsService.orderedVetters(marketplace.id()).stream()
                        .map(Vetter::name)
                        .toList(),
                order.override(),
                order.source(),
                orderSetting == null ? null : orderSetting.reason(),
                orderSetting == null ? null : orderSetting.updatedBy(),
                orderSetting == null ? null : orderSetting.updatedAt());
    }

    @PutMapping("/vetting/chain-mode")
    @Requirements({"GW_VETTING_0032", "GW_VETTING_0032.1", "GW_VETTING_0032.4"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Set how far the vetting chain runs",
            description = "Sets the chain mode, globally or for one named marketplace, and records the change"
                    + " on the audit ledger. Under 'stop-after-fail' the chain stops after the first vetter whose"
                    + " verdict fails; the vetters after it are recorded as not reached rather than run, and a run"
                    + " carrying such a verdict is blocked whatever waivers exist — accepting the finding that"
                    + " stopped the chain takes effect on the next run, not on this one. 'run-all' is the default"
                    + " and runs every enabled vetter. A per-marketplace setting overrides the global one."
                    + " Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The setting after the change")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    @ApiResponse(responseCode = "422", description = "Unknown or omitted mode")
    public ChainModeSetting setMode(@RequestBody ChainModeRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        if (request == null || request.mode() == null || request.mode().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "the 'mode' field is required (run-all or stop-after-fail)");
        }
        ChainMode mode;
        try {
            mode = ChainMode.of(request.mode());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return settingsService.setMode(mode, request.marketplace(), request.reason(), authentication.getName());
    }

    @PutMapping("/vetting/chain-order")
    @Requirements({"GW_VETTING_0033", "GW_VETTING_0033.1"})
    @Tag(name = "Vetting")
    @Operation(
            summary = "Set the order the vetters run in",
            description = "Sets the vetter order, globally or for one named marketplace, and records the change"
                    + " on the audit ledger. The order need not name every vetter: the ones it does not name run"
                    + " after those it does, in their configured positions with ties broken by name, so an order"
                    + " can never drop a vetter by omission. A name no configured vetter carries, and a name given"
                    + " twice, are refused rather than stored. Order is only observable when the chain mode stops"
                    + " it early. Administrator-only.")
    @ApiResponse(responseCode = "200", description = "The setting after the change")
    @ApiResponse(responseCode = "403", description = "Caller does not hold the administrative role")
    @ApiResponse(responseCode = "404", description = "Named marketplace not found")
    @ApiResponse(responseCode = "422", description = "Empty order, unknown vetter, or a vetter named twice")
    public ChainOrderSetting setOrder(@RequestBody ChainOrderRequest request, Authentication authentication) {
        roleService.requireAdmin(authentication);
        return settingsService.setOrder(
                request == null ? null : request.vetters(),
                request == null ? null : request.marketplace(),
                request == null ? null : request.reason(),
                authentication.getName());
    }

    private Marketplace marketplace(String name) {
        return marketplaceRepository
                .findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
    }
}
