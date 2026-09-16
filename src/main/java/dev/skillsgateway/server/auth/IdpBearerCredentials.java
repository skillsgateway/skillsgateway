package dev.skillsgateway.server.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An unauthenticated identity-provider bearer credential, as presented.
 *
 * <p>Its own type, for the reason {@code MachineApiCredentials} is: the facade now runs two
 * providers side by side, and typing the credential is what stops either from ever being handed
 * the other's. {@code PatAuthenticationProvider} supports
 * {@code UsernamePasswordAuthenticationToken}; this supports nothing else. A personal access
 * token pasted into a {@code Bearer} header therefore reaches the JWT provider and is refused
 * there, instead of falling through to a hash lookup that would have accepted it.
 */
public final class IdpBearerCredentials extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient String secret;

    public IdpBearerCredentials(String secret) {
        super(List.of());
        this.secret = secret;
        setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return secret;
    }

    @Override
    public Object getPrincipal() {
        return null;
    }
}
