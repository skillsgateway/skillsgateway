package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The machine-reachability registry: every mapped {@code /api/**} route is classified here, either
 * under a named API scope or on the explicit unreachable list. A route in neither fails the build
 * ({@code MachineApiRegistryTests}), so a new endpoint is unreachable until somebody names it —
 * an allowlist that surfaces an omission as a bug report rather than as an incident.
 *
 * <p>Reach is the <em>intersection</em> of this allowlist, the credential's named scopes and the
 * principal's roles, never their union. No scope value reaches anything on the unreachable list,
 * whatever combination of scopes is held and whatever role the principal has.
 *
 * <p>There is no wildcard scope and no scope implies another: {@code policy:write} does not confer
 * {@code policy:read}. Implication chains are how coarse scopes grow back, and narrowing a coarse
 * scope later would silently change what already-issued credentials mean.
 */
public final class MachineApiRegistry {

    /** One mapped route: an HTTP method and the pattern the application actually serves. */
    public record Route(String method, String pattern) implements Comparable<Route> {
        @Override
        public int compareTo(Route other) {
            int byPattern = pattern.compareTo(other.pattern);
            return byPattern != 0 ? byPattern : method.compareTo(other.method);
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private static Route get(String pattern) {
        return new Route("GET", pattern);
    }

    private static Route post(String pattern) {
        return new Route("POST", pattern);
    }

    private static Route put(String pattern) {
        return new Route("PUT", pattern);
    }

    private static Route delete(String pattern) {
        return new Route("DELETE", pattern);
    }

    /**
     * The reachable surface, scope by scope. Derived from the controller inventory rather than
     * from a sketch: re-vetting publishes nothing and is reachable, while retention's evaluate and
     * compact retract content and are not.
     */
    private static final Map<String, Set<Route>> REACHABLE = reachable();

    private static Map<String, Set<Route>> reachable() {
        Map<String, Set<Route>> scopes = new LinkedHashMap<>();
        scopes.put(
                "marketplaces:read",
                Set.of(
                        get("/api/marketplaces"),
                        get("/api/catalog"),
                        get("/api/snapshots/{id}/content"),
                        get("/api/snapshots/{id}/licenses"),
                        get("/api/snapshots/{id}/provenance"),
                        get("/api/snapshots/{id}/release-age")));
        scopes.put(
                "snapshots:read",
                Set.of(
                        get("/api/snapshots/{id}/diff"),
                        get("/api/snapshots/{id}/file"),
                        get("/api/snapshots/{id}/files"),
                        get("/api/snapshots/{id}/vetting"),
                        get("/api/snapshots/{id}/fetchers"),
                        // Four-eyes eligibility is a read of the same evidence surface: it reports
                        // whether a second reviewer is required and who the first was. Approval
                        // itself stays unreachable, which is what keeps this a read.
                        get("/api/snapshots/{id}/four-eyes")));
        scopes.put("marketplaces:register", Set.of(post("/api/marketplaces")));
        scopes.put("marketplaces:ingest", Set.of(post("/api/marketplaces/{name}/ingest")));
        scopes.put("vetting:run", Set.of(post("/api/marketplaces/{name}/revet"), post("/api/snapshots/{id}/revet")));
        scopes.put("waivers:read", Set.of(get("/api/marketplaces/{name}/waivers")));
        scopes.put("sync:write", Set.of(put("/api/marketplaces/{name}/sync")));
        scopes.put("catalog:rebuild", Set.of(post("/api/catalog/rebuild")));
        scopes.put(
                "webhooks:read",
                Set.of(get("/api/webhooks"), get("/api/webhooks/deliveries"), get("/api/webhooks/events")));
        scopes.put("webhooks:write", Set.of(post("/api/webhooks"), delete("/api/webhooks/{id}")));
        scopes.put("audit:read", Set.of(get("/api/audit"), get("/api/audit/export")));
        scopes.put("audit-sinks:read", Set.of(get("/api/audit/sinks")));
        scopes.put(
                "audit-sinks:write",
                Set.of(post("/api/audit/sinks"), delete("/api/audit/sinks/{id}"), put("/api/audit/sinks/{id}/cursor")));
        scopes.put(
                "policy:read",
                Set.of(
                        get("/api/policy/rules"),
                        // A POST inside a read scope, deliberately: the playground evaluates a
                        // policy against a candidate and persists nothing, so it reads.
                        post("/api/policy/playground")));
        scopes.put(
                "policy:write",
                Set.of(post("/api/policy/rules"), put("/api/policy/rules/{name}"), delete("/api/policy/rules/{name}")));
        scopes.put("retention:read", Set.of(get("/api/retention/candidates")));
        scopes.put("estate:read", Set.of(get("/api/estate")));
        scopes.put("estate:reconcile", Set.of(post("/api/estate/reconcile")));
        scopes.put("adoption:read", Set.of(get("/api/adoption"), get("/api/adoption/staleness")));
        scopes.put("roles:read", Set.of(get("/api/roles")));
        return Map.copyOf(scopes);
    }

    /**
     * Every act of human judgement and every endpoint that retracts or republishes content, plus
     * the whole credential-minting surface. No scope value reaches these and none may be added:
     * an entry here is a decision, and moving one out of this table is a security change.
     */
    private static final Set<Route> UNREACHABLE = Set.of(
            // Publishes content; human judgement.
            post("/api/snapshots/{id}/approve"),
            post("/api/snapshots/{id}/reject"),
            // Overrides the vetting chain, and withdraws that override; human judgement.
            post("/api/snapshots/{id}/waivers"),
            delete("/api/waivers/{id}"),
            // Soft-deletes every candidate it finds; purges permanently. Both retract content.
            post("/api/retention/evaluate"),
            post("/api/retention/compact"),
            delete("/api/snapshots/{id}"),
            post("/api/snapshots/{id}/restore"),
            // Privilege granting: estate.grants is the only route, with no credential in the
            // pipeline at all. A credential that can write grants can escalate what it reaches.
            post("/api/roles"),
            delete("/api/roles/{id}"),
            // Credential minting, including the machine-credential provisioning added by this
            // change. A credential that can mint a sibling can evade its own revocation.
            post("/api/tokens"),
            get("/api/tokens"),
            post("/api/tokens/session"),
            post("/api/tokens/{id}/rotate"),
            delete("/api/tokens/{id}"),
            post("/api/tokens/machine"),
            get("/api/tokens/machine"),
            post("/api/tokens/machine/{id}/rotate"),
            delete("/api/tokens/machine/{id}"),
            // A session identity page; a machine has no session.
            get("/api/me"),
            // The connector on/off switch (GW_0143): administrator judgement over the vetting
            // chain itself, and even seeing the current settings is reserved to administrators —
            // no scope may let a machine credential turn off the control that governs it.
            get("/api/vetting/connector-toggles"),
            put("/api/vetting/connectors/{name}/toggle"));

    private MachineApiRegistry() {}

    /** Every named scope value, in declaration order. There is no wildcard and no implicit all. */
    @Requirements({"GW_0129"})
    public static Set<String> scopes() {
        return REACHABLE.keySet();
    }

    /** The routes a single scope reaches. Empty for a value that is not a known scope. */
    @Requirements({"GW_0129"})
    public static Set<Route> routesOf(String scope) {
        return REACHABLE.getOrDefault(scope, Set.of());
    }

    /** Whether the value names a scope this gateway knows; a misspelling must fail loudly. */
    @Requirements({"GW_0126"})
    public static boolean isKnownScope(String scope) {
        return REACHABLE.containsKey(scope);
    }

    /** Every route on the explicit unreachable list. */
    public static Set<Route> unreachable() {
        return UNREACHABLE;
    }

    /** Every route the registry classifies, reachable and unreachable alike. */
    public static Set<Route> classified() {
        Set<Route> all = new LinkedHashSet<>(UNREACHABLE);
        REACHABLE.values().forEach(all::addAll);
        return Set.copyOf(all);
    }

    /** Every reachable route, paired with the single scope that reaches it. */
    public static List<Map.Entry<String, Route>> reachableRoutes() {
        return REACHABLE.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(route -> Map.entry(entry.getKey(), route)))
                .toList();
    }

    /** The scope that reaches a route, or empty when no scope does. */
    public static Optional<String> scopeFor(Route route) {
        return REACHABLE.entrySet().stream()
                .filter(entry -> entry.getValue().contains(route))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
