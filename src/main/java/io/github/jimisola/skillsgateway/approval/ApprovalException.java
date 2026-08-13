package io.github.jimisola.skillsgateway.approval;

public class ApprovalException extends RuntimeException {

    public ApprovalException(String message) {
        super(message);
    }

    public ApprovalException(String message, Throwable cause) {
        super(message, cause);
    }
}
