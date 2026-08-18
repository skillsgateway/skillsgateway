package dev.skillsgateway.server.policy;

/**
 * A rule that failed to produce a verdict at evaluation time (GW_0090): a runtime error inside the
 * expression, an exceeded evaluation bound, or facts that could not be built. The gate treats
 * every carrier of this exception as a denial — a rule that cannot say {@code false} has not said
 * it.
 */
public class PolicyEvaluationException extends RuntimeException {

    public PolicyEvaluationException(String message) {
        super(message);
    }

    public PolicyEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
