package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Automatic approval's refusals, for its own endpoints and for registration, which can turn it on
 * too.
 */
@RestControllerAdvice(assignableTypes = {AutoApprovalSettingController.class, AdminController.class})
public class AutoApprovalSettingAdvice {

    @ExceptionHandler(AutoApprovalSettingValidationException.class)
    public ProblemDetail invalid(AutoApprovalSettingValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("The automatic approval is incomplete or runs too long");
        return problem;
    }

    @ExceptionHandler(NotInternalException.class)
    public ProblemDetail notInternal(NotInternalException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Automatic approval needs an internal source or an administrator exemption");
        problem.setProperty("nextStep", e.nextStep());
        return problem;
    }

    /** A bulk withdrawal's return that a marketplace cannot make; raised before anything is withdrawn. */
    @ExceptionHandler(RollbackUnavailableException.class)
    public ProblemDetail rollbackUnavailable(RollbackUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("There is no previous approved snapshot to serve");
        problem.setProperty("marketplace", e.marketplace());
        return problem;
    }
}
