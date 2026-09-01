package dev.skillsgateway.server.roles;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Two startup refusals, both about the same removal.
 *
 * <p>Authorization used to be switchable, and it defaulted to off, so a gateway installed and left
 * alone granted full administrative access to anyone who could complete a login. Enforcement is now
 * unconditional (GW_0138), which trades that failure for a different one: an estate nobody can
 * administer. This guard makes both failures loud at startup instead of quiet at runtime, in the
 * shape {@code DevInsecureAuthGuard} already established for the sibling escape hatch — say what was
 * detected, why it matters, and every way out, quoting property names in full.
 */
@Component
@Requirements({"GW_0139", "GW_0140"})
public class RoleBootstrapGuard {

    /** The property that used to switch enforcement off. Refused rather than ignored. */
    static final String REMOVED_PROPERTY = "skills-gateway.roles.enabled";

    public RoleBootstrapGuard(SkillsGatewayProperties properties, Environment environment) {
        // Relaxed binding means one containsProperty call covers skills-gateway.roles.enabled,
        // SKILLS_GATEWAY_ROLES_ENABLED and every other spelling the framework would have resolved.
        if (environment.containsProperty(REMOVED_PROPERTY)) {
            throw new IllegalStateException(removedProperty());
        }
        if (properties.devInsecureAuth()) {
            // The escape hatch confers the administrative role on its own principal (GW_0141), so
            // the estate is administerable and there is nothing to check. DevInsecureAuthGuard
            // independently refuses that flag on anything resembling a real deployment, so this
            // skip cannot become a way around the check in production.
            return;
        }
        if (!administrable(properties)) {
            throw new IllegalStateException(noAdministrator());
        }
    }

    /**
     * Whether the configuration alone could give someone the administrative role.
     *
     * <p>Configuration only, deliberately. A stored grant is revocable through the API, so it says
     * nothing about whether the <em>next</em> start will have an administrator: deleting the last
     * one would break a later restart with no visible connection to the deletion that caused it. It
     * also keeps startup independent of database reachability and free of any ordering relationship
     * with the estate reconciler, which runs later.
     */
    private boolean administrable(SkillsGatewayProperties properties) {
        if (!properties.roles().admins().isEmpty()) {
            return true;
        }
        boolean mapped =
                properties.roles().mappings().stream().anyMatch(mapping -> RoleGrant.ADMIN.equals(mapping.role()));
        boolean declared =
                properties.estate().grants().stream().anyMatch(grant -> RoleGrant.ADMIN.equals(grant.role()));
        return mapped || declared;
    }

    private String removedProperty() {
        return """
                %s was removed, and this gateway refuses to start while it is set.

                Authorization is now always enforced, so there is nothing for this property to \
                turn on or off. Ignoring it silently would be worse than refusing: a deployment \
                that set it to false was asking for a gateway with no authorization, and quietly \
                doing the opposite of what a manifest says is not something an operator can see \
                from inside the deployment.

                Remove %s. If the intention was to keep a gateway that anyone who logs in can \
                administer, that is no longer available; grant the admin role to the principals \
                who should have it instead, with skills-gateway.roles.admins or a \
                skills-gateway.roles.mappings entry.

                This refusal is a migration aid and is scheduled for removal at the next major \
                version.""".formatted(REMOVED_PROPERTY, REMOVED_PROPERTY);
    }

    private String noAdministrator() {
        return """
                Authorization is always enforced, and this gateway has no administrator.

                Nothing in this deployment's configuration grants the admin role, so no identity \
                could administer the gateway once it started: no marketplace could be registered, \
                no snapshot approved and no role granted. Rather than start in that state, the \
                gateway refuses.

                Grant the admin role in one of these ways and restart:
                  * skills-gateway.roles.admins - a list of principals that are admins by \
                configuration and that no API call can revoke;
                  * skills-gateway.roles.mappings - a mapping from one of your identity provider's \
                claim values to the admin role;
                  * skills-gateway.estate.grants - a declared grant of the admin role.

                A stored grant made through the API does not satisfy this check, because it can be \
                revoked through the API: the check reads configuration so that it says something \
                about every start, not only this one.

                For local development, skills-gateway.dev-insecure-auth=true skips this check and \
                makes its own principal an admin. It refuses to start against a configured \
                identity provider, so it is not a way to run a real deployment without an \
                administrator.""";
    }
}
