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
        boolean sessionDerived,
        String apiScopes,
        String machineOwner) {

    /** The scope list, empty meaning every marketplace (GW_0064). */
    public List<String> scopeList() {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(scopes.split(","));
    }

    /**
     * Whether this token may fetch the named repository through the facade (GW_0064, GW_0127).
     *
     * <p>An empty fetch list means <em>every marketplace</em> — what every pre-scoping token
     * meant — but only for a credential that holds no administrative scope. A machine API
     * credential necessarily has an empty fetch list, so the unconditional default would have
     * handed it the entire estate the moment it authenticated on the facade: the exact opposite
     * of the guarantee that a control-plane credential confers no fetch. For such a credential an
     * empty fetch list therefore means <em>nothing</em>.
     *
     * <p>This is the only thing the facade learns about administrative scope, and it learns it
     * here rather than at the chain, so no caller can forget to ask. See {@link #apiScopeList()}
     * for all three empty-list meanings stated together.
     */
    public boolean permitsMarketplace(String marketplace) {
        List<String> scopeList = scopeList();
        if (scopeList.isEmpty()) {
            return !machineCredential();
        }
        return scopeList.contains(marketplace);
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

    /**
     * The administrative scope list; empty means this token reaches no {@code /api/**} endpoint
     * at all (GW_0126).
     *
     * <p>All three of this record's scope dimensions have an empty value, and all three mean
     * something different. Stated together, because reading any one of them as "unrestricted" is
     * where the next defect lives:
     *
     * <ul>
     *   <li><b>fetch</b> ({@code scopes}) — empty means <em>every marketplace</em>, which is what
     *       every pre-scoping token meant, <em>unless</em> {@code apiScopes} is non-empty, in
     *       which case it means nothing. See {@link #permitsMarketplace}.
     *   <li><b>push</b> ({@code pushScopes}) — empty means <em>nowhere</em>. It was added after
     *       scoping existed, so it never had a permissive default.
     *   <li><b>API</b> ({@code apiScopes}) — empty means <em>nothing</em>, deliberately the push
     *       default rather than the fetch one: reaching the control plane is a grant and never a
     *       baseline, so a fetch token must not become a machine credential by omission.
     * </ul>
     */
    public List<String> apiScopeList() {
        if (apiScopes == null || apiScopes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(apiScopes.split(","));
    }

    /**
     * Whether this is a machine API credential (GW_0126) — exactly when it holds at least one
     * administrative scope. There is no separate credential type and no flag: one record carries
     * all three dimensions, and each chain asks only the dimension it owns.
     */
    public boolean machineCredential() {
        return !apiScopeList().isEmpty();
    }

    /**
     * Whether this token holds the named administrative scope (GW_0126). Exact membership: there
     * is no wildcard value and no scope implies another, so {@code policy:write} does not confer
     * {@code policy:read}. Implication chains are how coarse scopes grow back.
     */
    public boolean permitsApiScope(String scope) {
        return apiScopeList().contains(scope);
    }
}
