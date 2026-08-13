package io.github.jimisola.skillsgateway.facade;

import io.github.jimisola.skillsgateway.persistence.FetchLogRepository;
import io.github.reqstool.annotations.Requirements;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class FetchAuditHook {

    private final FetchLogRepository fetchLogRepository;

    public FetchAuditHook(FetchLogRepository fetchLogRepository) {
        this.fetchLogRepository = fetchLogRepository;
    }

    @Requirements({"GW_0008"})
    public void record(String source, String principal, String marketplace, String event, String ref, String sha) {
        fetchLogRepository.append(source, principal, marketplace, event, ref, sha);
    }

    /** Name of the authenticated principal, or {@code null} when absent or anonymous. */
    public String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
