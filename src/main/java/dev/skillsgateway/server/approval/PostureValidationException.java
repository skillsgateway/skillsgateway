package dev.skillsgateway.server.approval;

/** A posture or override request missing what makes it reviewable, or asking for too long (GW_APPROVAL_0044.1). */
public class PostureValidationException extends RuntimeException {

    public PostureValidationException(String message) {
        super(message);
    }
}
