package dev.skillsgateway.server.approval;

/**
 * An automatic-approval or exemption request missing what makes it reviewable, or asking for too
 * long (GW_APPROVAL_0044.1).
 */
public class AutoApprovalSettingValidationException extends RuntimeException {

    public AutoApprovalSettingValidationException(String message) {
        super(message);
    }
}
