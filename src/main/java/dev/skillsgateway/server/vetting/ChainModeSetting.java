package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A standing administrative decision about how far the chain runs, globally or for a single
 * marketplace (GW_VETTING_0032.1).
 *
 * <p>A {@code null} {@link #marketplaceId()} is the global setting; a row naming a marketplace
 * overrides it for that marketplace. The absence of any row means {@link ChainMode#RUN_ALL}, so an
 * empty table is exactly the behaviour before the mode existed.
 *
 * @param reason the administrator's optional note, mirrored onto the ledger when the mode is set
 */
@Schema(description = "An administrative chain-mode setting")
public record ChainModeSetting(
        @Schema(description = "Setting id") long id,

        @Schema(description = "Marketplace this setting is scoped to, or null for the global setting")
        Long marketplaceId,

        @Schema(description = "How far the chain runs under this setting")
        ChainMode mode,

        @Schema(description = "The administrator's note for the setting, or null")
        String reason,

        @Schema(description = "Identity that last set it") String updatedBy,
        @Schema(description = "When it was last set") Instant updatedAt) {}
