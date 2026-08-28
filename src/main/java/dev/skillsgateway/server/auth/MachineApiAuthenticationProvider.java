package dev.skillsgateway.server.auth;

import io.github.reqstool.annotations.Requirements;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Authenticates a bearer credential on the machine API chain (GW_0127).
 *
 * <p>The negative guarantee lives here, as a <b>precondition of authentication</b> rather than as
 * an authorization rule a controller could forget to call: a credential is authenticated only
 * when its administrative scope list is non-empty. A perfectly valid facade token — including the
 * every-marketplace form, the most permissive fetch grant the system has — therefore does not
 * authenticate on this chain at all. There is no path by which a fetch or publication credential
 * becomes a control-plane credential, because there is no code that would let it.
 *
 * <p>Every failure raises the same exception with the same message, so a caller cannot tell an
 * unknown secret from a known one that lacks administrative scope.
 */
@Component
public class MachineApiAuthenticationProvider implements AuthenticationProvider {

    /** One message for every refusal: nothing here distinguishes why. */
    private static final String REFUSED = "invalid credential";

    private final TokenService tokenService;

    public MachineApiAuthenticationProvider(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    @Requirements({"GW_0127"})
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Object credentials = authentication.getCredentials();
        if (credentials == null || credentials.toString().isBlank()) {
            throw new BadCredentialsException(REFUSED);
        }
        return tokenService
                .authenticate(credentials.toString())
                // Revocation and expiry are already applied by the lookup (GW_0065), so a
                // revoked or expired credential never reaches this filter.
                .filter(token -> token.machineCredential())
                .<Authentication>map(MachineApiAuthentication::new)
                .orElseThrow(() -> new BadCredentialsException(REFUSED));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return MachineApiCredentials.class.isAssignableFrom(authentication);
    }
}
