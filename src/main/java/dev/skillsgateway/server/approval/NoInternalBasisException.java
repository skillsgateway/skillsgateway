package dev.skillsgateway.server.approval;

/**
 * {@code auto} asked for on a marketplace that is not internal and has no active override
 * (GW_APPROVAL_0044.3). The answer names the override, the one way to proceed.
 */
public class NoInternalBasisException extends RuntimeException {

    public NoInternalBasisException(String marketplace) {
        super("marketplace '%s' is not under an internal prefix and is not hosted, so automatic approval needs an"
                        .formatted(marketplace)
                + " override");
    }

    public String nextStep() {
        return ("set the posture with PUT /api/v1/marketplaces/{name}/approval-posture and an override,"
                        + " {\"override\": {\"reason\": ..., \"expiresAt\": ...}}, running at most %d days; or add the"
                        + " marketplace's organisation to %s")
                .formatted(
                        ApprovalPostureService.OVERRIDE_MAX_LIFETIME.toDays(),
                        dev.skillsgateway.server.config.SkillsGatewayProperties.Auto.INTERNAL_PREFIXES_KEY);
    }
}
