package dev.skillsgateway.server.persistence;

/**
 * A snapshot whose content was fetched from a URL its marketplace no longer has (GW_INGEST_0066): the
 * URL was corrected while the ingest ran, and recording it would attribute the content to the new one.
 */
public class MarketplaceUrlChangedException extends RuntimeException {

    public MarketplaceUrlChangedException(long marketplaceId) {
        super("the URL of marketplace %d changed while it was being ingested; ingest again to fetch from the new URL"
                .formatted(marketplaceId));
    }
}
