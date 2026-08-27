package dev.skillsgateway.server.roles;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.ClaimMapping;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Roles;
import dev.skillsgateway.server.roles.RoleService.EffectiveRole;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Roles the identity provider already knows about (GW_0098): a configured claim's values matched
 * against a configured table of claim value to role. Nothing here is provider-specific — the claim
 * name, its path and every value come from configuration, because on an app registration shared
 * with other services the values are the organisation's, not the gateway's.
 *
 * <p>Two rules carry the trust boundary. The match is exact on a trimmed value, so a mapping can
 * never widen who is privileged by resembling something. And claims are read only from an {@link
 * OidcUser}: a personal access token, the {@code dev-insecure-auth} principal and an anonymous
 * request have no claims and derive nothing, whatever authorities they carry.
 *
 * <p>The mapping table is validated at construction, so a deployment with a malformed mapping
 * refuses to start rather than silently conferring nothing.
 */
@Component
public class ClaimRoleMapper {

    private static final Logger log = LoggerFactory.getLogger(ClaimRoleMapper.class);

    /** OpenID Connect Core's marker for a claim delivered elsewhere rather than in the token. */
    private static final String DISTRIBUTED_CLAIM_NAMES = "_claim_names";

    private static final Set<String> ROLES = Set.of(RoleGrant.ADMIN, RoleGrant.APPROVER, RoleGrant.AUDITOR);

    private final String claim;
    private final List<String> claimPath;
    private final List<ClaimMapping> mappings;

    @Autowired
    public ClaimRoleMapper(SkillsGatewayProperties properties) {
        this(properties.roles());
    }

    /** The mapping table on its own — the shape the validation and the matcher actually need. */
    public ClaimRoleMapper(Roles roles) {
        // Roles normalises a missing or blank claim to its default, so there is nothing to guard.
        this.claim = roles.claim();
        this.claimPath = List.of(this.claim.split("\\."));
        this.mappings = validated(roles.mappings());
        if (!this.mappings.isEmpty()) {
            log.info("{} identity-provider role mapping(s) configured against claim '{}'", mappings.size(), claim);
        }
    }

    /**
     * Every mapping the deployment declared, or no application at all. A typo that quietly grants
     * nothing is the failure this refuses: an operator who misspells a role learns at startup.
     */
    @Requirements({"GW_0098"})
    private List<ClaimMapping> validated(List<ClaimMapping> declared) {
        List<ClaimMapping> checked = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            ClaimMapping mapping = declared.get(i);
            String where = "skills-gateway.roles.mappings[%d]".formatted(i);
            if (mapping.claimValue() == null || mapping.claimValue().isBlank()) {
                throw new IllegalStateException("%s needs a claim-value".formatted(where));
            }
            if (mapping.role() == null || !ROLES.contains(mapping.role())) {
                throw new IllegalStateException(
                        "%s names role '%s', which is not one of %s".formatted(where, mapping.role(), ROLES));
            }
            boolean approver = RoleGrant.APPROVER.equals(mapping.role());
            boolean scoped =
                    mapping.marketplace() != null && !mapping.marketplace().isBlank();
            if (approver && !scoped) {
                throw new IllegalStateException(
                        "%s is an approver mapping and must name a marketplace".formatted(where));
            }
            if (!approver && scoped) {
                throw new IllegalStateException(
                        "%s is a %s mapping and cannot name a marketplace".formatted(where, mapping.role()));
            }
            checked.add(new ClaimMapping(
                    mapping.claimValue().trim(),
                    mapping.role(),
                    scoped ? mapping.marketplace().trim() : null));
        }
        return List.copyOf(checked);
    }

    /** The roles this session's claims confer, in mapping order, without duplicates. */
    @Requirements({"GW_0098"})
    public List<EffectiveRole> rolesFrom(Authentication authentication) {
        if (mappings.isEmpty()) {
            return List.of();
        }
        Set<String> values = claimValues(authentication);
        if (values.isEmpty()) {
            return List.of();
        }
        Set<EffectiveRole> roles = new LinkedHashSet<>();
        for (ClaimMapping mapping : mappings) {
            if (values.contains(mapping.claimValue())) {
                roles.add(new EffectiveRole(mapping.role(), mapping.marketplace(), EffectiveRole.CLAIM));
            }
        }
        return List.copyOf(roles);
    }

    /**
     * Whether the provider dropped the claim rather than the session having no membership
     * (GW_0099). Two shapes say so without naming any product: OpenID Connect's {@code
     * _claim_names}, and a sibling boolean named after the claim — the {@code hasgroups} convention
     * a directory uses when a user belongs to more groups than a token may carry.
     */
    @Requirements({"GW_0099"})
    public boolean truncated(Authentication authentication) {
        Optional<Map<String, Object>> claims = claimsOf(authentication);
        if (claims.isEmpty() || resolve(claims.get()) != null) {
            return false;
        }
        Map<String, Object> all = claims.get();
        if (all.get(DISTRIBUTED_CLAIM_NAMES) instanceof Map<?, ?> names && names.containsKey(claimPath.getFirst())) {
            return true;
        }
        return Boolean.TRUE.equals(all.get("has" + claimPath.getLast()));
    }

    /** Trimmed claim values, from a list or a lone string; a delimited string is never split. */
    private Set<String> claimValues(Authentication authentication) {
        Object value = claimsOf(authentication).map(this::resolve).orElse(null);
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof String text) {
                    values.add(text.trim());
                }
            }
        } else if (value instanceof String text) {
            values.add(text.trim());
        }
        return values;
    }

    /** Walks the dotted claim path, so a provider that nests membership needs no adapter. */
    private Object resolve(Map<String, Object> claims) {
        Object current = claims;
        for (String segment : claimPath) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** Claims exist only for a browser session established through the identity provider. */
    private Optional<Map<String, Object>> claimsOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser user
                ? Optional.ofNullable(user.getClaims())
                : Optional.empty();
    }
}
