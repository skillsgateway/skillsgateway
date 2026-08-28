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
        Long rotatedFrom,
        String pushScopes,
        boolean sessionDerived) {

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

    /** The push scope list; empty means this token may push nowhere (GW_0102). */
    public List<String> pushScopeList() {
        if (pushScopes == null || pushScopes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(pushScopes.split(","));
    }

    /**
     * Whether this token may push to the named hosted marketplace (GW_0102). Deliberately the
     * opposite default from {@link #permitsMarketplace}: no push scopes means none, so every
     * token that predates publication — and every token whose fetch scope is the
     * every-marketplace form — can write nothing.
     */
    public boolean permitsPushTo(String marketplace) {
        return pushScopeList().contains(marketplace);
    }
}
