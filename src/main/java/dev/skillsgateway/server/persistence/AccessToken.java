package dev.skillsgateway.server.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public record AccessToken(
        long id,
        String principal,
        String name,
        String tokenHash,
        Instant createdAt,
        Instant revokedAt,
        String scopes,
        Instant expiresAt,
        Long rotatedFrom) {

    /** The scope list, empty meaning every marketplace (GW_0064). */
    public List<String> scopeList() {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(scopes.split(","));
    }

    /**
     * Whether this token may fetch the named repository through the facade (GW_0064). No scopes
     * means every marketplace — what every pre-scoping token meant.
     */
    public boolean permitsMarketplace(String marketplace) {
        List<String> scopeList = scopeList();
        return scopeList.isEmpty() || scopeList.contains(marketplace);
    }
}
