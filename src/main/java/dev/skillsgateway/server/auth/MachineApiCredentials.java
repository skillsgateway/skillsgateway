package dev.skillsgateway.server.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An unauthenticated bearer credential, as presented. Its own type so that
 * {@link MachineApiAuthenticationProvider} supports exactly this and the facade's
 * {@code PatAuthenticationProvider} — which supports {@code UsernamePasswordAuthenticationToken}
 * — can never be handed one by accident.
 */
public final class MachineApiCredentials extends AbstractAuthenticationToken {

    private final transient String secret;

    public MachineApiCredentials(String secret) {
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
