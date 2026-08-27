package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Stateless PAT-over-Basic chain for git clients; 401 + Basic challenge on failure. */
    @Bean
    @Order(1)
    public SecurityFilterChain gitChain(HttpSecurity http, PatAuthenticationProvider patAuthenticationProvider)
            throws Exception {
        http.securityMatcher("/git/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(new ProviderManager(patAuthenticationProvider))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        return http.build();
    }

    /**
     * The publication chain (GW_0102): the only write path the gateway has, and a sibling of the
     * read-only facade chain rather than a mode on it. Same credential kind — PATs, stateless, no
     * session — but authorization is the token's push scope, which no token holds by default and
     * none can hold for every marketplace.
     */
    @Bean
    @Order(2)
    @Requirements({"GW_0102"})
    public SecurityFilterChain publishChain(HttpSecurity http, PatAuthenticationProvider patAuthenticationProvider)
            throws Exception {
        http.securityMatcher("/publish/**")
                // No CSRF token, for the same reason as the facade chain: a PAT over
                // Basic on every request, STATELESS, no cookie or session created or
                // honoured. A forged cross-site request arrives unauthenticated, and
                // would still need a token carrying push scope for this marketplace.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(new ProviderManager(patAuthenticationProvider))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Stateless anonymous chain for the inbound forge webhook (GW_0058). Authentication is not
     * absent, it lives one layer down: the controller verifies an HMAC-SHA256 signature of the raw
     * body against the marketplace's gateway-generated secret and rejects everything else. Keeping
     * the check out of the filter chain also keeps it in force under dev-insecure-auth, and the
     * controller takes no Authentication parameter (requests here are anonymous by design).
     */
    @Bean
    @Order(3)
    public SecurityFilterChain hooksChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/hooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Adds the issuer comparison Spring Security cannot make on its own here (GW_0100). Unset is
     * today's behaviour and stays the default, so an upgrade changes nothing — but a deployment
     * that is not the local development escape hatch is told, once, that its login accepts an
     * identity token from any issuer whose keys are in the configured key set.
     */
    @Bean
    @Requirements({"GW_0100"})
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(SkillsGatewayProperties properties) {
        String issuer = properties.oidc().issuer();
        if ((issuer == null || issuer.isBlank()) && !properties.devInsecureAuth()) {
            log.warn("skills-gateway.oidc.issuer is not set — the identity token's issuer is not checked. "
                    + "Pin it to your provider's issuer, which on a multi-tenant endpoint is the tenant boundary.");
        }
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> OidcIdTokenValidation.validator(registration, issuer));
        return factory;
    }

    /**
     * OIDC for browsers; unauthenticated /api/** gets 401 instead of a login redirect.
     *
     * <p>With {@code skills-gateway.dev-insecure-auth=true} (development only, default off) the
     * web surface is open and requests act as the anonymous user "dev"; the git facade keeps
     * requiring PATs. GW_0011 holds for every default-configured deployment.
     */
    @Bean
    @Order(4)
    @Requirements({"GW_0011"})
    public SecurityFilterChain webChain(HttpSecurity http, SkillsGatewayProperties properties) throws Exception {
        if (properties.devInsecureAuth()) {
            log.warn("skills-gateway.dev-insecure-auth is ON — the web surface is UNAUTHENTICATED. "
                    + "Never enable this outside local development.");
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    // Synthetic principal so controllers taking Authentication keep working.
                    .addFilterBefore(
                            (request, response, chain) -> {
                                SecurityContext context = SecurityContextHolder.getContext();
                                if (context.getAuthentication() == null) {
                                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                            "dev", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                                }
                                chain.doFilter(request, response);
                            },
                            AnonymousAuthenticationFilter.class)
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
            return http.build();
        }
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2Login(Customizer.withDefaults())
                // Session-cookie API for the SPA; revisit CSRF with the portal.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
        return http.build();
    }
}
