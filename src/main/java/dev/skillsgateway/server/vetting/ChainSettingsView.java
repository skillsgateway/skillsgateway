package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A marketplace's effective chain-level settings (GW_VETTING_0033.2): how far the chain goes, the
 * order it goes in, and which setting decided each.
 *
 * <p>A sibling of the effective chain rather than a wrapper around it. The chain read answers with
 * an array and is part of a contract that is additive within a major, so the settings that are not
 * per-vetter get their own resource instead of changing that array's shape.
 *
 * @param order every configured vetter in the order this marketplace runs them, whether or not an
 *     administrator named them; the arrangement that was actually set is {@code orderOverride}
 */
@Schema(description = "A marketplace's effective vetting chain settings")
public record ChainSettingsView(
        @Schema(description = "How far the chain runs for this marketplace")
        ChainMode mode,

        @Schema(description = "Which setting decided the mode")
        ChainSource modeSource,

        @Schema(description = "The note recorded with the deciding mode setting, or null for the default")
        String modeReason,

        @Schema(description = "The administrator who last set the mode, or null for the default")
        String modeUpdatedBy,

        @Schema(description = "When the mode was last set, or null for the default")
        Instant modeUpdatedAt,

        @Schema(description = "Every configured vetter, in the order this marketplace runs them")
        List<String> order,

        @Schema(description = "The arrangement an administrator set, or empty when none is set")
        List<String> orderOverride,

        @Schema(description = "Which setting decided the order")
        ChainSource orderSource,

        @Schema(description = "The note recorded with the deciding order setting, or null for the default")
        String orderReason,

        @Schema(description = "The administrator who last set the order, or null for the default")
        String orderUpdatedBy,

        @Schema(description = "When the order was last set, or null for the default")
        Instant orderUpdatedAt) {

    public ChainSettingsView {
        order = order == null ? List.of() : List.copyOf(order);
        orderOverride = orderOverride == null ? List.of() : List.copyOf(orderOverride);
    }
}
