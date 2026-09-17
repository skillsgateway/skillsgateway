package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A standing administrative decision about the order the vetters run in, globally or for a single
 * marketplace (GW_VETTING_0033.1).
 *
 * <p>Scoped and resolved exactly as {@link ChainModeSetting} is. The list need not name every
 * vetter: the ones it does not name follow in their configured positions, which is what keeps an
 * override from silently dropping a vetter a later release adds.
 *
 * @param vetters the vetter names an administrator arranged, in the order they arranged them
 * @param reason the administrator's optional note, mirrored onto the ledger when the order is set
 */
@Schema(description = "An administrative vetter-order setting")
public record ChainOrderSetting(
        @Schema(description = "Setting id") long id,

        @Schema(description = "Marketplace this setting is scoped to, or null for the global setting")
        Long marketplaceId,

        @Schema(description = "The vetter names, in the order the administrator arranged them")
        List<String> vetters,

        @Schema(description = "The administrator's note for the setting, or null")
        String reason,

        @Schema(description = "Identity that last set it") String updatedBy,
        @Schema(description = "When it was last set") Instant updatedAt) {

    public ChainOrderSetting {
        vetters = vetters == null ? List.of() : List.copyOf(vetters);
    }
}
