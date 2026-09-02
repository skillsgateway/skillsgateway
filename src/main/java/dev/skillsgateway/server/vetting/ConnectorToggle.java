package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A standing administrative decision to enable or disable one built-in vetting connector, globally
 * or for a single marketplace (GW_0149).
 *
 * <p>A {@code null} {@link #marketplaceId()} is the global setting; a row that names a marketplace
 * overrides the global one for that marketplace. The absence of any row for a connector means it is
 * enabled, so an empty table is exactly the behaviour before this feature existed: every connector
 * runs.
 *
 * @param marketplaceId the marketplace this setting is scoped to, or null for the global setting
 * @param reason the administrator's optional note, mirrored onto the ledger when the toggle is set
 */
@Schema(description = "An administrative enable/disable setting for one built-in vetting connector")
public record ConnectorToggle(
        @Schema(description = "Setting id") long id,

        @Schema(description = "The connector's stable name, e.g. secret-scan")
        String connector,

        @Schema(description = "Marketplace this setting is scoped to, or null for the global setting")
        Long marketplaceId,

        @Schema(description = "Whether the connector runs under this setting")
        boolean enabled,

        @Schema(description = "The administrator's note for the setting, or null")
        String reason,

        @Schema(description = "Identity that last set it") String updatedBy,
        @Schema(description = "When it was last set") Instant updatedAt) {}
