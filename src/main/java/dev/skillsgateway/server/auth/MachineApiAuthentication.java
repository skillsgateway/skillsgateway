package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.persistence.AccessToken;
import io.github.reqstool.annotations.Requirements;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * An authenticated machine API credential (GW_0127). Its own type rather than a
 * {@code UsernamePasswordAuthenticationToken} so that the ledger, the audit logger and any future
 * caller can tell a machine actor from a person by asking the authentication rather than by
 * comparing strings — which is the same mistake this change removes from the ledger.
 *
 * <p>The authorities are the credential's named administrative scopes, prefixed {@code SCOPE_}.
 * They are never roles: a role is {@code RoleService}'s to decide from configuration and stored
 * grants, and baking one into a credential would create a second authorization model that
 * revoking a row could not reach.
 */
public final class MachineApiAuthentication extends AbstractAuthenticationToken {

    /** Authority prefix for a named administrative scope. */
    public static final String SCOPE_PREFIX = "SCOPE_";

    private final transient AccessToken token;

    public MachineApiAuthentication(AccessToken token) {
        super(authorities(token));
        this.token = token;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(AccessToken token) {
        return token.apiScopeList().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority(SCOPE_PREFIX + scope))
                .toList();
    }

    /** The credential this request authenticated with; the ledger attributes the entry to it. */
    @Requirements({"GW_0127"})
    public AccessToken token() {
        return token;
    }

    @Override
    public Object getCredentials() {
        // The secret is never retained: it was hashed and compared once, at authentication.
        return "n/a";
    }

    @Override
    public Object getPrincipal() {
        return token.principal();
    }

    @Override
    public String getName() {
        return token.principal();
    }

    /** The authorities this credential's scopes confer, as plain scope values. */
    public List<String> scopes() {
        return token.apiScopeList();
    }
}
