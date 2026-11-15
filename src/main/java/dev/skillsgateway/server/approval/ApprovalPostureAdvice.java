package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.admin.AdminController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The posture's refusals, for its own endpoints and for registration, which takes a posture too. */
@RestControllerAdvice(assignableTypes = {ApprovalPostureController.class, AdminController.class})
public class ApprovalPostureAdvice {

    @ExceptionHandler(PostureValidationException.class)
    public ProblemDetail invalid(PostureValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("The approval posture is incomplete or runs too long");
        return problem;
    }

    @ExceptionHandler(NoInternalBasisException.class)
    public ProblemDetail noInternalBasis(NoInternalBasisException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Automatic approval needs an internal source or an override");
        problem.setProperty("nextStep", e.nextStep());
        return problem;
    }
}
