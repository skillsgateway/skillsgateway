package dev.skillsgateway.server.admin;

import io.github.reqstool.annotations.Requirements;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The words used when a client or an estate file still asks for gateway-hosted marketplaces.
 *
 * <p>These requests are refused rather than ignored. The request and estate types keep the removed
 * names ({@code origin}, {@code pushPolicy}, {@code pushScopes}) as hidden components for exactly
 * this, so that a request for hosting does not turn into a registration that silently is not hosted,
 * nor a request for a push token into a token that cannot push (GW_AUTH_0090). Deleting those
 * components as dead fields would turn the refusal back into silence.
 */
@Requirements({"GW_AUTH_0090"})
public final class RemovedHosting {

    private RemovedHosting() {}

    /** What the refusal says, naming the removal and where the replacement procedure is written. */
    public static String message(String asked) {
        return "hosted marketplaces were removed: %s is no longer supported. Keep the skills on a forge and register"
                        .formatted(asked)
                + " that repository as an upstream (see the guide removing-hosted-marketplaces)";
    }

    /** The {@code 400} the API answers with. */
    public static ResponseStatusException refusal(String asked) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message(asked));
    }
}
