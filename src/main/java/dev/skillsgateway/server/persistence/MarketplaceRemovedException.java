package dev.skillsgateway.server.persistence;

/** A decision about a snapshot whose marketplace was removed (GW_INGEST_0034): it has nowhere to publish. */
public class MarketplaceRemovedException extends RuntimeException {

    public MarketplaceRemovedException(long snapshotId) {
        super("snapshot %d belongs to a marketplace that was removed; it can no longer be decided"
                .formatted(snapshotId));
    }
}
