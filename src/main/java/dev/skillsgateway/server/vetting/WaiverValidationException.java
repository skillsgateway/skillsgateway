package dev.skillsgateway.server.vetting;

/**
 * A waiver was requested that cannot be an accepted risk: no justification, no expiry, an expiry
 * already in the past, or a scope that names nothing (GW_VETTING_0007). Rejected before anything is
 * written, so a refused request leaves no partial waiver behind.
 */
public class WaiverValidationException extends RuntimeException {

    public WaiverValidationException(String message) {
        super(message);
    }
}
