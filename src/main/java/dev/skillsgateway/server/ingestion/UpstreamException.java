package dev.skillsgateway.server.ingestion;

/**
 * The upstream could not be read (GW_INGEST_0038). Raised only by {@link UpstreamGit}, so a caller can
 * tell the upstream's answer apart from a failure of the gateway's own.
 */
public class UpstreamException extends IngestionException {

    UpstreamException(UpstreamFailure failure, Throwable cause) {
        super(failure.describe(), failure, cause);
    }
}
