package io.github.jimisola.skillsgateway.auth;

import io.github.reqstool.annotations.Requirements;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/** Git clients send HTTP Basic where the password is the PAT; the username is ignored. */
@Component
public class PatAuthenticationProvider implements AuthenticationProvider {

    private final TokenService tokenService;

    public PatAuthenticationProvider(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    @Requirements({"GW_0012"})
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            throw new BadCredentialsException("missing token");
        }
        return tokenService
                .authenticate(credentials.toString())
                .map(accessToken -> {
                    UsernamePasswordAuthenticationToken authenticated =
                            UsernamePasswordAuthenticationToken.authenticated(
                                    accessToken.principal(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_GIT")));
                    // The token rides with the authentication so the facade can enforce its
                    // scopes (GW_0064) and the audit hook can attribute the fetch to it
                    // (GW_0067) without a second lookup.
                    authenticated.setDetails(accessToken);
                    return (Authentication) authenticated;
                })
                .orElseThrow(() -> new BadCredentialsException("invalid token"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
