package dev.skillsgateway.server.approval;

/**
 * A withdrawal did not say what the marketplace serves afterwards (GW_APPROVAL_0016).
 *
 * <p>There is no default, deliberately. Returning to the previous approved snapshot republishes
 * older content that may carry the same compromise — a poisoned transitive dependency is usually
 * present there too — while serving nothing takes down a marketplace whose predecessor may be
 * obviously clean. Only the person holding the reason knows which, so the request is refused rather
 * than resolved on their behalf.
 */
public class MissingServeAfterChoiceException extends RuntimeException {

    public MissingServeAfterChoiceException(long snapshotId) {
        super("withdrawing snapshot %d requires saying what the marketplace serves afterwards:"
                + " PREVIOUS_APPROVED or NOTHING".formatted(snapshotId));
    }
}
