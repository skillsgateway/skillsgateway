package dev.skillsgateway.server.vetting;

/**
 * Something an automatic approval reads changed for a marketplace's held snapshots: a snapshot was
 * ingested, its evidence refreshed, or a waiver created (GW_APPROVAL_0044). Published, not called,
 * so ingestion and vetting do not depend on the approval package that listens.
 *
 * @param marketplaceId the marketplace whose newest held snapshot is worth attempting again
 * @param cause what changed, for the log
 */
public record HeldSnapshotChanged(long marketplaceId, String cause) {

    /** The re-vetting tick ran: one bounded step attempts every automatic marketplace again. */
    public record RevetTick() {}
}
