package dev.skillsgateway.server.facade;

import dev.skillsgateway.server.observability.GatewayMetrics;
import dev.skillsgateway.server.persistence.AccessToken;
import dev.skillsgateway.server.persistence.FetchLogRepository;
import io.github.reqstool.annotations.Requirements;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class FetchAuditHook {

    private final FetchLogRepository fetchLogRepository;
    private final GatewayMetrics metrics;

    public FetchAuditHook(FetchLogRepository fetchLogRepository, GatewayMetrics metrics) {
        this.fetchLogRepository = fetchLogRepository;
        this.metrics = metrics;
    }

    /**
     * Facade entries carry the id of the token that authenticated them (GW_0067): a principal
     * with several tokens is several distinct credentials, and a leak trace needs to know which
     * one fetched.
     */
    @Requirements({"GW_0008", "GW_0067"})
    public void record(String source, String principal, String marketplace, String event, String ref, String sha) {
        AccessToken token = currentToken();
        fetchLogRepository.append(
                source, principal, marketplace, event, ref, sha, null, token == null ? null : token.id());
        // Counter only (GW_0077), tagged by the closed event vocabulary — never by marketplace,
        // SHA or principal; those dimensions live in the ledger and the adoption API.
        metrics.facadeFetch(event);
    }

    /** Name of the authenticated principal, or {@code null} when absent or anonymous. */
    public String currentPrincipal() {
        Authentication authentication = current();
        return authentication == null ? null : authentication.getName();
    }

    /** The access token that authenticated this request, or {@code null} when none did. */
    public AccessToken currentToken() {
        Authentication authentication = current();
        return authentication != null && authentication.getDetails() instanceof AccessToken token ? token : null;
    }

    private static Authentication current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
