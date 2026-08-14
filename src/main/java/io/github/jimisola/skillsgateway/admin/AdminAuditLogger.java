package io.github.jimisola.skillsgateway.admin;

import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
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
        fetchLogRepository.append(SOURCE, principal, marketplace, event, null, sha);
    }
}
