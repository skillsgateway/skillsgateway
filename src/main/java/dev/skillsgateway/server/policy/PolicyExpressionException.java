package dev.skillsgateway.server.policy;

/**
 * A CEL expression that does not compile against the documented policy variables, or does not
 * produce a boolean (GW_0089). Raised at write time — creation, update, declaration, playground —
 * so a stored rule is always a compiled one.
 */
public class PolicyExpressionException extends RuntimeException {

    public PolicyExpressionException(String message) {
        super(message);
    }

    public PolicyExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
