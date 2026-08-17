package dev.skillsgateway.server.admin;

import dev.skillsgateway.server.persistence.FetchLogRepository;
import io.github.reqstool.annotations.Requirements;
import org.springframework.stereotype.Component;

/**
 * Appends administrative actions (GW_0022) to the same append-only ledger as facade fetches:
 * registration, ingestion, approval decisions, and token lifecycle, each with the acting identity.
 */
@Component
public class AdminAuditLogger {

    /** Ledger source marker distinguishing admin actions from git facade fetches. */
    private static final String SOURCE = "admin";

    private final FetchLogRepository fetchLogRepository;

    public AdminAuditLogger(FetchLogRepository fetchLogRepository) {
        this.fetchLogRepository = fetchLogRepository;
    }

    @Requirements({"GW_0022"})
    public void record(String principal, String marketplace, String event, String sha) {
        record(principal, marketplace, event, sha, null);
    }

    /** As {@link #record}, carrying the entry's free-text qualifier (a vetting outcome or reason). */
    @Requirements({"GW_0022", "GW_0043"})
    public void record(String principal, String marketplace, String event, String sha, String detail) {
        fetchLogRepository.append(SOURCE, principal, marketplace, event, null, sha, detail);
    }
}
