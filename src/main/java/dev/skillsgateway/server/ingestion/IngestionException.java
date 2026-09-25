package dev.skillsgateway.server.ingestion;

public class IngestionException extends RuntimeException {

    /** Why, in an operator's words (GW_INGEST_0038); null for a failure raised without one. */
    private final transient UpstreamFailure failure;

    public IngestionException(String message) {
        super(message);
        this.failure = null;
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
        this.failure = null;
    }

    public IngestionException(String message, UpstreamFailure failure, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public UpstreamFailure failure() {
        return failure;
    }
}
