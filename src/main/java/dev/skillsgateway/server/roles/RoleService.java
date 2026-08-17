package dev.skillsgateway.server.roles;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.persistence.MarketplaceRepository;
import dev.skillsgateway.server.persistence.SnapshotRepository;
import dev.skillsgateway.server.vetting.WaiverRepository;
import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The authorization surface of the web chain (GW_0068–GW_0071): explicit {@code require*} calls
 * at the first line of every privileged controller method, deny-by-default once enabled. The
 * whole surface is greppable as {@code requireA}; the facade is out of scope (its authorization
 * is token scopes, GW_0064).
 *
 * <p>With {@code skills-gateway.roles.enabled=false} — the default — every check passes, so an
 * upgrade never locks anyone out. Roles compose upward: admin ⊇ approver, admin ⊇ auditor.
 */
@Service
public class RoleService {

    /** A caller's effective role, config-bootstrapped admins appearing as a synthetic entry. */
    @Schema(description = "An effective role held by the current session")
    public record EffectiveRole(
            @Schema(
                    description = "The role",
                    allowableValues = {"admin", "approver", "auditor"})
            String role,

            @Schema(description = "Marketplace an approver role is scoped to; null for the global roles")
            String marketplace) {}

    private final SkillsGatewayProperties properties;
    private final RoleGrantRepository roleGrantRepository;
    private final SnapshotRepository snapshotRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final WaiverRepository waiverRepository;

    public RoleService(
            SkillsGatewayProperties properties,
            RoleGrantRepository roleGrantRepository,
            SnapshotRepository snapshotRepository,
            MarketplaceRepository marketplaceRepository,
            WaiverRepository waiverRepository) {
        this.properties = properties;
        this.roleGrantRepository = roleGrantRepository;
        this.snapshotRepository = snapshotRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.waiverRepository = waiverRepository;
    }

    public boolean enabled() {
        return properties.roles().enabled();
    }

    /** Global mutations: registration, sync modes, catalog, retention, receivers, sinks, grants. */
    @Requirements({"GW_0068"})
    public void requireAdmin(Authentication authentication) {
        if (!enabled() || isAdmin(authentication.getName())) {
            return;
        }
        throw denied();
    }

    /** The ledger, its export, and the operational listings: auditor or admin (GW_0070). */
    @Requirements({"GW_0068", "GW_0070"})
    public void requireAuditor(Authentication authentication) {
        if (!enabled()) {
            return;
        }
        String principal = authentication.getName();
        if (isAdmin(principal) || hasGlobalRole(principal, RoleGrant.AUDITOR)) {
            return;
        }
        throw denied();
    }

    /** Marketplace-named routes: an approver of exactly this marketplace, or an admin. */
    @Requirements({"GW_0069"})
    public void requireApprover(Authentication authentication, String marketplaceName) {
        if (!enabled()) {
            return;
        }
        String principal = authentication.getName();
        if (isAdmin(principal) || approves(principal, marketplaceName)) {
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
        if (!enabled()) {
            return;
        }
        String principal = authentication.getName();
        if (isAdmin(principal)) {
            return;
        }
        if (marketplaceName.isPresent() && approves(principal, marketplaceName.get())) {
            return;
        }
        throw denied();
    }

    /** Effective roles for the session endpoint; a config admin appears as a synthetic entry. */
    @Requirements({"GW_0071"})
    public List<EffectiveRole> rolesOf(String principal) {
        List<EffectiveRole> roles = new ArrayList<>();
        if (properties.roles().admins().contains(principal)) {
            roles.add(new EffectiveRole(RoleGrant.ADMIN, null));
        }
        for (RoleGrant grant : roleGrantRepository.findByPrincipal(principal)) {
            EffectiveRole role = new EffectiveRole(grant.role(), grant.marketplace());
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    /** Admins by configuration cannot be revoked through the API: they are never rows (GW_0071). */
    @Requirements({"GW_0071"})
    private boolean isAdmin(String principal) {
        return properties.roles().admins().contains(principal) || hasGlobalRole(principal, RoleGrant.ADMIN);
    }

    private boolean hasGlobalRole(String principal, String role) {
        return roleGrantRepository.findByPrincipal(principal).stream()
                .anyMatch(grant -> grant.role().equals(role));
    }

    private boolean approves(String principal, String marketplaceName) {
        return roleGrantRepository.findByPrincipal(principal).stream()
                .anyMatch(grant ->
                        grant.role().equals(RoleGrant.APPROVER) && marketplaceName.equals(grant.marketplace()));
    }

    /** One denial for every unauthorized call: no role, wrong role, wrong marketplace, unknown id. */
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "this operation requires a role you do not hold");
    }
}
