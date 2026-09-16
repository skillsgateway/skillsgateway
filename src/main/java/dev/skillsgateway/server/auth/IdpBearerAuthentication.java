package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A facade request authenticated by an identity-provider bearer token (GW_AUTH_0040).
 *
 * <p>Its own type rather than a {@code UsernamePasswordAuthenticationToken} carrying a marker,
 * because two things downstream ask what authenticated a request and both have to be able to
 * answer without a string comparison: the ledger, which records the credential kind (GW_AUTH_0041),
 * and the facade's scope check, which reads the PAT row out of {@code details} and must find
 * nothing here.
 *
 * <p>{@code details} is deliberately left null. An identity-provider token names no
 * {@code access_tokens} row, so there is no scope list to enforce and no token id to attribute —
 * which is exactly the state an unscoped PAT's holder already gets, and is why the facade needed
 * no new branch.
 */
@Requirements({"GW_AUTH_0040"})
public final class IdpBearerAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient Jwt token;
    private final String principal;

    IdpBearerAuthentication(String principal, Jwt token) {
        // ROLE_GIT, the same authority the PAT provider grants: this chain authorizes on
        // authentication, and granting anything else here would be a privilege the credential
        // does not carry.
        super(List.of(new SimpleGrantedAuthority("ROLE_GIT")));
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    /** The validated token, kept so a caller can read a claim without decoding it again. */
    public Jwt token() {
        return token;
    }

    @Override
    public Object getCredentials() {
        // The bearer value is not retained: nothing downstream needs it, and a credential kept
        // on an object that reaches logging is a credential that eventually reaches a log.
        return "n/a";
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal;
    }
}
