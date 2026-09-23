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
                        get("/api/v1/marketplaces"),
                        get("/api/v1/catalog"),
                        get("/api/v1/snapshots/{id}/content"),
                        // Beside the inventory rather than with the preview reads: it returns the
                        // same plugin and skill names that /content already gives this scope, plus
                        // those of an approved snapshot the facade is serving anyway, and no file
                        // content at all.
                        get("/api/v1/snapshots/{id}/content-diff"),
                        get("/api/v1/snapshots/{id}/licenses"),
                        get("/api/v1/snapshots/{id}/provenance"),
                        get("/api/v1/snapshots/{id}/release-age")));
        scopes.put(
                "snapshots:read",
                Set.of(
                        get("/api/v1/snapshots/{id}/diff"),
                        get("/api/v1/snapshots/{id}/file"),
                        get("/api/v1/snapshots/{id}/files"),
                        get("/api/v1/snapshots/{id}/vetting"),
                        // The only route in this scope that also needs a role: the blast-radius
                        // report is approver-scoped (GW_AUTH_0011), and reach is the intersection.
                        get("/api/v1/snapshots/{id}/fetchers"),
                        // Four-eyes eligibility is a read of the same evidence surface: it reports
                        // whether a second reviewer is required and who the first was. Approval
                        // itself stays unreachable, which is what keeps this a read.
                        get("/api/v1/snapshots/{id}/four-eyes"),
                        // The name-collision report is the same kind of read: what an approval would
                        // meet, deciding nothing. It names incumbents' marketplaces and plugin names,
                        // which the catalog already publishes.
                        get("/api/v1/snapshots/{id}/name-collisions")));
        scopes.put("marketplaces:register", Set.of(post("/api/v1/marketplaces")));
        scopes.put("marketplaces:ingest", Set.of(post("/api/v1/marketplaces/{name}/ingest")));
        scopes.put(
                "vetting:run", Set.of(post("/api/v1/marketplaces/{name}/revet"), post("/api/v1/snapshots/{id}/revet")));
        scopes.put("waivers:read", Set.of(get("/api/v1/marketplaces/{name}/waivers")));
        scopes.put("sync:write", Set.of(put("/api/v1/marketplaces/{name}/sync")));
        scopes.put("catalog:rebuild", Set.of(post("/api/v1/catalog/rebuild")));
        scopes.put(
                "webhooks:read",
                Set.of(get("/api/v1/webhooks"), get("/api/v1/webhooks/deliveries"), get("/api/v1/webhooks/events")));
        scopes.put("webhooks:write", Set.of(post("/api/v1/webhooks"), delete("/api/v1/webhooks/{id}")));
        scopes.put("audit:read", Set.of(get("/api/v1/audit"), get("/api/v1/audit/export")));
        scopes.put("audit-sinks:read", Set.of(get("/api/v1/audit/sinks")));
        scopes.put(
                "audit-sinks:write",
                Set.of(
                        post("/api/v1/audit/sinks"),
                        delete("/api/v1/audit/sinks/{id}"),
                        put("/api/v1/audit/sinks/{id}/cursor")));
        scopes.put(
                "policy:read",
                Set.of(
                        get("/api/v1/policy/rules"),
                        // A POST inside a read scope, deliberately: the playground evaluates a
                        // policy against a candidate and persists nothing, so it reads.
                        post("/api/v1/policy/playground")));
        scopes.put(
                "policy:write",
                Set.of(
                        post("/api/v1/policy/rules"),
                        put("/api/v1/policy/rules/{name}"),
                        delete("/api/v1/policy/rules/{name}")));
        scopes.put("retention:read", Set.of(get("/api/v1/retention/candidates")));
        scopes.put("estate:read", Set.of(get("/api/v1/estate")));
        scopes.put("estate:reconcile", Set.of(post("/api/v1/estate/reconcile")));
        scopes.put("adoption:read", Set.of(get("/api/v1/adoption"), get("/api/v1/adoption/staleness")));
        scopes.put("roles:read", Set.of(get("/api/v1/roles")));
        return Map.copyOf(scopes);
    }

    /**
     * Every act of human judgement and every endpoint that retracts or republishes content, plus
     * the whole credential-minting surface. No scope value reaches these and none may be added:
     * an entry here is a decision, and moving one out of this table is a security change.
     */
    private static final Set<Route> UNREACHABLE = Set.of(
            // Publishes content; human judgement.
            post("/api/v1/snapshots/{id}/approve"),
            post("/api/v1/snapshots/{id}/reject"),
            // Withdraws served content on knowledge no machine holds (GW_APPROVAL_0015). The reason
            // is the whole of the accountability for an act that takes one identity and no second
            // reviewer, and a credential in a pipeline cannot supply one that means anything.
            post("/api/v1/snapshots/{id}/revoke"),
            // Overrides the vetting chain, and withdraws that override; human judgement.
            post("/api/v1/snapshots/{id}/waivers"),
            delete("/api/v1/waivers/{id}"),
            // Soft-deletes every candidate it finds; purges permanently. Both retract content.
            post("/api/v1/retention/evaluate"),
            post("/api/v1/retention/compact"),
            delete("/api/v1/snapshots/{id}"),
            post("/api/v1/snapshots/{id}/restore"),
            // Privilege granting: estate.grants is the only route, with no credential in the
            // pipeline at all. A credential that can write grants can escalate what it reaches.
            post("/api/v1/roles"),
            delete("/api/v1/roles/{id}"),
            // Credential minting, including the machine-credential provisioning added by this
            // change. A credential that can mint a sibling can evade its own revocation.
            post("/api/v1/tokens"),
            get("/api/v1/tokens"),
            post("/api/v1/tokens/session"),
            post("/api/v1/tokens/{id}/rotate"),
            delete("/api/v1/tokens/{id}"),
            post("/api/v1/tokens/machine"),
            get("/api/v1/tokens/machine"),
            post("/api/v1/tokens/machine/{id}/rotate"),
            delete("/api/v1/tokens/machine/{id}"),
            // A session identity page; a machine has no session.
            get("/api/v1/me"),
            // The forge mirror's drift report (GW_FACADE_0023): it names an outbound integration target
            // and the state of its credential's last use, which is deployment infrastructure
            // rather than anything the gateway serves. Administrator-only, and no scope reaches it.
            get("/api/v1/mirror/drift"),
            // Reconciling that mirror on demand (GW_FACADE_0027): the same outbound integration, and the
            // route that actually exercises its credential against the forge. If the report is
            // reserved to administrators, the button that acts on it cannot be less so.
            post("/api/v1/mirror/reconcile"),
            // The vetter on/off switch (GW_VETTING_0029.4): administrator judgement over the vetting
            // chain itself, and even seeing the current settings is reserved to administrators —
            // no scope may let a machine credential turn off the control that governs it.
            get("/api/v1/vetting/vetter-toggles"),
            put("/api/v1/vetting/vetters/{name}/toggle"),
            // The same settings resolved for one marketplace (GW_VETTING_0029.5). Reading which
            // vetters a marketplace actually runs is reading the settings themselves, so it is
            // unreachable for the same reason the settings list is.
            get("/api/v1/marketplaces/{name}/vetting-chain"),
            // How far the chain runs and the order it runs in (GW_VETTING_0032, GW_VETTING_0033):
            // the same administrator judgement over the chain as the switch, reserved on the same
            // terms. A machine credential that could stop the chain early, or move a vetter behind
            // the one that stops it, would be deciding how much evidence gates an approval.
            get("/api/v1/vetting/chain-settings"),
            put("/api/v1/vetting/chain-mode"),
            put("/api/v1/vetting/chain-order"),
            get("/api/v1/marketplaces/{name}/vetting-chain-settings"),
            // The same three settings one resolution level up, and across the estate
            // (GW_VETTING_0035, GW_VETTING_0036, GW_VETTING_0037). Unreachable for the reason above,
            // only more so: the bulk route is the one call that could narrow every marketplace's
            // chain at once, and removing an override is how a marketplace stops being governed by
            // the setting an administrator thought they had pinned it to.
            get("/api/v1/vetting/global-chain"),
            get("/api/v1/vetting/global-chain-settings"),
            post("/api/v1/vetting/chain-settings/bulk"));

    private MachineApiRegistry() {}

    /** Every named scope value, in declaration order. There is no wildcard and no implicit all. */
    @Requirements({"GW_AUTH_0022"})
    public static Set<String> scopes() {
        return REACHABLE.keySet();
    }

    /** The routes a single scope reaches. Empty for a value that is not a known scope. */
    @Requirements({"GW_AUTH_0022"})
    public static Set<Route> routesOf(String scope) {
        return REACHABLE.getOrDefault(scope, Set.of());
    }

    /** Whether the value names a scope this gateway knows; a misspelling must fail loudly. */
    @Requirements({"GW_AUTH_0020"})
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
