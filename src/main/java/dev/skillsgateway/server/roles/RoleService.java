package dev.skillsgateway.server.roles;

import dev.skillsgateway.server.admin.AdminAuditLogger;
import dev.skillsgateway.server.auth.SecurityConfig;
import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.Marketplace;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.vetting.WaiverRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The authorization surface of the web chain (GW_0068–GW_0071): explicit {@code require*} calls
 * at the first line of every privileged controller method, deny-by-default once enabled. The
 * whole surface is greppable as {@code requireA}; the facade is out of scope (its authorization
 * is token scopes, GW_0064).
 *
 * <p>Enforcement is unconditional (GW_0138): there is no configuration that makes a check pass for
 * a principal holding no role. A deployment that configures no administrator is refused at startup
 * by {@link RoleBootstrapGuard} rather than started in a state nobody can administer. Roles compose
 * upward: admin ⊇ approver, admin ⊇ auditor.
 */
@Service
public class RoleService {

    /**
     * A caller's effective role and where it came from: configuration admins and identity-provider
     * claims appear as synthetic entries alongside stored grants, so the session endpoint can
     * answer why a session holds a role without anyone reading the deployment's configuration.
     */
    @Schema(description = "An effective role held by the current session")
    public record EffectiveRole(
            @Schema(
                    description = "The role",
                    allowableValues = {"admin", "approver", "auditor"})
            String role,

            @Schema(description = "Marketplace an approver role is scoped to; null for the global roles")
            String marketplace,

            @Schema(
                    description = "Where the role came from",
                    allowableValues = {"config", "grant", "claim", "dev-insecure-auth"})
            String source) {

        /** Listed in {@code skills-gateway.roles.admins}; no API call can revoke it. */
        public static final String CONFIG = "config";

        /** A row in {@code role_grants}. */
        public static final String GRANT = "grant";

        /** Derived from the identity provider's claims by {@link ClaimRoleMapper}. */
        public static final String CLAIM = "claim";

        /**
         * Conferred by the development escape hatch on its own synthetic principal (GW_0141).
         *
         * <p>Named separately from {@link #CONFIG} on purpose: the session endpoint exists to
         * answer why a session holds a role, and an operator told that this admin came from
         * configuration would go looking for a configuration entry that does not exist.
         */
        public static final String DEV_INSECURE_AUTH = "dev-insecure-auth";
    }

    private static final Set<String> ROLES = Set.of(RoleGrant.ADMIN, RoleGrant.APPROVER, RoleGrant.AUDITOR);

    /** Grant administration is not itself tied to a marketplace; the ledger column is NOT NULL. */
    private static final String NO_MARKETPLACE = "-";

    private final SkillsGatewayProperties properties;
    private final RoleGrantRepository roleGrantRepository;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final WaiverRepository waiverRepository;
    private final ClaimRoleMapper claimRoleMapper;
    private final AdminAuditLogger auditLogger;

    public RoleService(
            SkillsGatewayProperties properties,
            RoleGrantRepository roleGrantRepository,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            WaiverRepository waiverRepository,
            ClaimRoleMapper claimRoleMapper,
            AdminAuditLogger auditLogger) {
        this.properties = properties;
        this.roleGrantRepository = roleGrantRepository;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.waiverRepository = waiverRepository;
        this.claimRoleMapper = claimRoleMapper;
        this.auditLogger = auditLogger;
    }

    /**
     * The one grant path (GW_0071, GW_0085): validates, inserts, and appends the ledger entry with
     * the acting identity — whether the caller is the grants API or the estate reconciler. Statuses
     * match the API contract; a non-HTTP caller reports the reason instead.
     */
    @Requirements({"GW_0071"})
    public RoleGrant grant(String principal, String role, String marketplace, String actor) {
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "a principal is required");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "role must be one of %s".formatted(ROLES));
        }
        Long marketplaceId = resolveScope(role, marketplace);
        RoleGrant grant = roleGrantRepository
                .insert(principal, role, marketplaceId, actor)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "that grant already exists for '%s'".formatted(principal)));
        auditLogger.record(
                actor,
                grant.marketplace() == null ? NO_MARKETPLACE : grant.marketplace(),
                "role-granted",
                null,
                "principal=%s role=%s".formatted(grant.principal(), grant.role()));
        return grant;
    }

    /** An approver grant is scoped to one existing marketplace; the global roles must not be. */
    @Requirements({"GW_0071"})
    private Long resolveScope(String role, String marketplace) {
        boolean approver = RoleGrant.APPROVER.equals(role);
        boolean scoped = marketplace != null && !marketplace.isBlank();
        if (approver && !scoped) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "an approver grant is scoped to one marketplace");
        }
        if (!approver && scoped) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "a %s grant is global and cannot name a marketplace".formatted(role));
        }
        if (!approver) {
            return null;
        }
        return marketplaceRepository
                .findByName(marketplace)
                .map(Marketplace::id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "marketplace '%s' not found".formatted(marketplace)));
    }

    /**
     * Global mutations: registration, sync modes, catalog, retention, receivers, sinks, grants, and
     * machine-credential provisioning (GW_0068, GW_0130, GW_0138).
     *
     * <p>This used to have a twin, {@code requireAdminRegardlessOfEnforcement}, which existed only
     * because the other {@code require*} methods could be switched off and machine-credential
     * provisioning must not be — a credential outlives the session that minted it, so a login that
     * could mint one holding every scope would leave privilege behind after the account itself was
     * deprovisioned. With enforcement unconditional the distinction it drew no longer exists, and
     * keeping two methods that do the same thing would invite a caller to pick the wrong one.
     */
    @Requirements({"GW_0068", "GW_0130", "GW_0138"})
    public void requireAdmin(Authentication authentication) {
        if (isAdmin(authentication)) {
            return;
        }
        throw denied();
    }

    /** The ledger, its export, and the operational listings: auditor or admin (GW_0070). */
    @Requirements({"GW_0068", "GW_0070", "GW_0138"})
    public void requireAuditor(Authentication authentication) {
        List<EffectiveRole> roles = effectiveRoles(authentication);
        if (isAdmin(roles) || hasGlobalRole(roles, RoleGrant.AUDITOR)) {
            return;
        }
        throw denied();
    }

    /** Marketplace-named routes: an approver of exactly this marketplace, or an admin. */
    @Requirements({"GW_0069", "GW_0138"})
    public void requireApprover(Authentication authentication, String marketplaceName) {
        List<EffectiveRole> roles = effectiveRoles(authentication);
        if (isAdmin(roles) || approves(roles, marketplaceName)) {
            return;
        }
        throw denied();
    }

    /**
     * Snapshot-id routes: the owning marketplace is resolved on the gateway's side, never from
     * the route, closing the confused-deputy path through a bare id (GW_0069). A snapshot that
     * does not exist denies a non-admin rather than answering 404, so an unauthorized caller
     * cannot probe which ids exist; an admin falls through to the controller's own 404.
     */
    @Requirements({"GW_0069"})
    public void requireApproverOfSnapshot(Authentication authentication, long snapshotId) {
        requireApproverOf(
                authentication,
                snapshotRepository
                        .findById(snapshotId)
                        .flatMap(snapshot -> marketplaceRepository.findById(snapshot.marketplaceId()))
                        .map(marketplace -> marketplace.name()));
    }

    /** Waiver-id routes: as {@link #requireApproverOfSnapshot}, resolved through the waiver. */
    @Requirements({"GW_0069"})
    public void requireApproverOfWaiver(Authentication authentication, long waiverId) {
        requireApproverOf(authentication, waiverRepository.findById(waiverId).map(waiver -> waiver.marketplace()));
    }

    private void requireApproverOf(Authentication authentication, Optional<String> marketplaceName) {
        List<EffectiveRole> roles = effectiveRoles(authentication);
        if (isAdmin(roles)) {
            return;
        }
        if (marketplaceName.isPresent() && approves(roles, marketplaceName.get())) {
            return;
        }
        throw denied();
    }

    /**
     * Everything a session may do: configuration admin, stored grants, and the roles its
     * identity-provider claims confer (GW_0071, GW_0098). The same (role, marketplace) pair is
     * reported once, attributed to the most durable source that produced it — a grant outranks a
     * claim, because a grant survives the user leaving the group.
     */
    @Requirements({"GW_0071", "GW_0098", "GW_0141"})
    public List<EffectiveRole> effectiveRoles(Authentication authentication) {
        Map<String, EffectiveRole> roles = new LinkedHashMap<>();
        if (escapeHatchPrincipal(authentication)) {
            EffectiveRole hatch = new EffectiveRole(RoleGrant.ADMIN, null, EffectiveRole.DEV_INSECURE_AUTH);
            roles.put(key(hatch), hatch);
        }
        for (EffectiveRole role : rolesOf(authentication.getName())) {
            roles.putIfAbsent(key(role), role);
        }
        for (EffectiveRole role : claimRoleMapper.rolesFrom(authentication)) {
            roles.putIfAbsent(key(role), role);
        }
        return List.copyOf(roles.values());
    }

    /** Whether the provider dropped the membership claim rather than the session having none. */
    @Requirements({"GW_0099"})
    public boolean claimsTruncated(Authentication authentication) {
        return claimRoleMapper.truncated(authentication);
    }

    /**
     * Roles the gateway itself stores for a principal: the configuration admin list and grant
     * rows, and deliberately not claim-derived roles — this is what {@code EstateReconciler} asks
     * before writing a declared grant, and it has no session to read claims from. Folding claims
     * in here would make a group membership suppress a declared grant, so losing the group would
     * silently lose the grant too.
     */
    @Requirements({"GW_0071"})
    public List<EffectiveRole> rolesOf(String principal) {
        List<EffectiveRole> roles = new ArrayList<>();
        if (properties.roles().admins().contains(principal)) {
            roles.add(new EffectiveRole(RoleGrant.ADMIN, null, EffectiveRole.CONFIG));
        }
        for (RoleGrant grant : roleGrantRepository.findByPrincipal(principal)) {
            EffectiveRole role = new EffectiveRole(grant.role(), grant.marketplace(), EffectiveRole.GRANT);
            if (roles.stream().noneMatch(existing -> key(existing).equals(key(role)))) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    /**
     * Whether this session is the one the development escape hatch invents (GW_0141).
     *
     * <p>Three conditions, each closing a way this could become more than a local convenience. The
     * flag must be on, so nothing is conferred in a deployment that never opened the hatch. The
     * principal must be the synthetic name exactly. And it must not be an {@link OidcUser}, so a
     * real identity-provider account that happens to be called {@code dev} gains nothing here — the
     * hatch replaces the provider rather than coexisting with it, and a session that came through a
     * provider did not come through the hatch.
     *
     * <p>The check lives in this service rather than in the filter chain so that
     * {@link #effectiveRoles} and every {@code require*} decide from the same fact; a role the
     * session endpoint reports but the checks do not honour, or the reverse, would be worse than
     * either answer.
     */
    private boolean escapeHatchPrincipal(Authentication authentication) {
        return properties.devInsecureAuth()
                && SecurityConfig.DEV_PRINCIPAL.equals(authentication.getName())
                && !(authentication.getPrincipal() instanceof OidcUser);
    }

    /** Identity of a role for de-duplication: what it grants, not where it came from. */
    private static String key(EffectiveRole role) {
        return role.role() + "\u0000" + Objects.toString(role.marketplace(), "");
    }

    /** Admins by configuration cannot be revoked through the API: they are never rows (GW_0071). */
    @Requirements({"GW_0071"})
    private boolean isAdmin(Authentication authentication) {
        return isAdmin(effectiveRoles(authentication));
    }

    private static boolean isAdmin(List<EffectiveRole> roles) {
        return hasGlobalRole(roles, RoleGrant.ADMIN);
    }

    private static boolean hasGlobalRole(List<EffectiveRole> roles, String role) {
        return roles.stream().anyMatch(effective -> effective.role().equals(role));
    }

    private static boolean approves(List<EffectiveRole> roles, String marketplaceName) {
        return roles.stream()
                .anyMatch(effective ->
                        effective.role().equals(RoleGrant.APPROVER) && marketplaceName.equals(effective.marketplace()));
    }

    /** One denial for every unauthorized call: no role, wrong role, wrong marketplace, unknown id. */
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "this operation requires a role you do not hold");
    }
}
