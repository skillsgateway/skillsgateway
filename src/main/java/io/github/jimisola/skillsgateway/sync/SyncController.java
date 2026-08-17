package io.github.jimisola.skillsgateway.sync;

import io.github.jimisola.skillsgateway.persistence.Marketplace;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The sync-mode admin surface (GW_0056), behind the OIDC session like every /api endpoint. */
@RestController
@RequestMapping("/api")
public class SyncController {

    private static final Set<String> MODES =
            Set.of(Marketplace.SYNC_ON_DEMAND, Marketplace.SYNC_SCHEDULED, Marketplace.SYNC_WEBHOOK);

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @Schema(description = "Sync mode change request")
    public record ChangeSyncModeRequest(
            @Schema(
                    description = "The new sync mode",
                    allowableValues = {"on-demand", "scheduled", "webhook"})
            String mode) {}

    @Schema(description = "The updated marketplace and, only when webhook mode was enabled, its secret")
    public record SyncModeView(
            Marketplace marketplace,

            @Schema(
                    description = "HMAC secret for the inbound webhook — returned exactly once, here."
                            + " Setting webhook mode again rotates it. Null for the other modes.")
            String webhookSecret) {}

    @PutMapping("/marketplaces/{name}/sync")
    @Requirements({"GW_0056"})
    @Tag(name = "Sync")
    @Operation(
            summary = "Change a marketplace's sync mode",
            description = "Sets how upstream content reaches quarantine: on-demand (operator-triggered,"
                    + " the default), scheduled (the gateway polls upstream), or webhook (a signed forge"
                    + " push webhook triggers ingestion). Enabling webhook mode generates the HMAC secret"
                    + " and returns it exactly once — re-enabling rotates it, and no read endpoint ever"
                    + " returns it. No mode bypasses approval: sync-triggered snapshots land held.")
    @ApiResponse(responseCode = "200", description = "Sync mode changed")
    @ApiResponse(responseCode = "404", description = "Marketplace not found")
    @ApiResponse(responseCode = "422", description = "Not a valid sync mode")
    public SyncModeView changeSyncMode(
            @PathVariable String name, @RequestBody ChangeSyncModeRequest request, Authentication authentication) {
        if (request.mode() == null || !MODES.contains(request.mode())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "mode must be one of %s".formatted(MODES));
        }
        SyncService.ModeChange change = syncService
                .changeMode(name, request.mode(), authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(name)));
        return new SyncModeView(change.marketplace(), change.webhookSecret());
    }
}
