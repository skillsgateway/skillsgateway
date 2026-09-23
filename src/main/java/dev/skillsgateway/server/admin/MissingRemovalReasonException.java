package dev.skillsgateway.server.admin;

/**
 * An administrator asked to remove a marketplace without stating a reason (GW_INGEST_0034). Required
 * for the reason a withdrawal's is: removal withdraws every approved snapshot on one identity's word,
 * and the reason is what each of those withdrawals records.
 */
public class MissingRemovalReasonException extends RuntimeException {

    public MissingRemovalReasonException(String marketplace) {
        super("removing marketplace '%s' requires a non-empty reason".formatted(marketplace));
    }
}
