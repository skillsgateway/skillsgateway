package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.config.SkillsGatewayProperties;

/**
 * Automatic approval turned on for a marketplace that is not internal and has no active exemption
 * (GW_APPROVAL_0044.3). The answer names the exemption, the one way to proceed.
 */
public class NotInternalException extends RuntimeException {

    public NotInternalException(String marketplace) {
        super("marketplace '%s' is not under an internal prefix and is not hosted, so automatic approval needs an"
                        .formatted(marketplace)
                + " administrator exemption");
    }

    public String nextStep() {
        return ("turn it on with PUT /api/v1/marketplaces/{name}/auto-approval and an exemption,"
                        + " {\"enabled\": true, ..., \"exemption\": {\"reason\": ..., \"expiresAt\": ...}}, running"
                        + " at most %d days; or add the marketplace's organisation to %s")
                .formatted(
                        AutoApprovalSettingService.EXEMPTION_MAX_LIFETIME.toDays(),
                        SkillsGatewayProperties.Auto.INTERNAL_PREFIXES_KEY);
    }
}
