package dev.skillsgateway.server.auth;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
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
import org.springframework.security.web.util.matcher.AndRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Stateless PAT-over-Basic chain for git clients; 401 + Basic challenge on failure. */
    @Bean
    @Order(1)
    public SecurityFilterChain gitChain(HttpSecurity http, PatAuthenticationProvider patAuthenticationProvider)
            throws Exception {
        http.securityMatcher("/git/**")
                // No CSRF token: this chain carries no ambient credential for a
                // third-party page to ride. Every request authenticates itself with a
                // PAT over Basic, no session is created, and no cookie is honoured, so
                // a forged cross-site request arrives unauthenticated.
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
                // No CSRF token: nothing here is authorized by a cookie or a session.
                // The controller's HMAC check over the raw body is the whole
                // authorization, and a browser cannot produce that signature.
                // Exempted rather than disabled, so a path added to this chain later
                // is protected by default instead of inheriting the exemption.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/hooks/**"))
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
     * The machine API chain (GW_0127): a sibling of the facade and publication chains rather than
     * a mode on the session chain. It matches {@code /api/**} <em>and</em> the presence of an
     * {@code Authorization: Bearer} header, so a browser request without one still falls through
     * to the session chain exactly as before and that chain is unchanged apart from its order.
     *
     * <p>Authentication is {@link MachineApiAuthenticationProvider}, which authenticates only a
     * credential holding at least one administrative scope. Authorization is
     * {@link MachineApiRegistry}, expanded here into one rule per reachable route with
     * {@code denyAll} underneath: every route the registry does not name — every act of human
     * judgement, every retraction of content, every credential-minting path — is refused whatever
     * combination of scopes the credential holds. Because both live in the filter chain, neither
     * consults {@code skills-gateway.roles.enabled}: that flag exists so an upgrade does not lock
     * out existing sessions, and nothing predates a credential kind that did not exist.
     *
     * <p>Ordered ahead of the web chain, so this holds under
     * {@code skills-gateway.dev-insecure-auth=true} as well — the escape hatch opens the browser
     * surface, never the bearer path. The facade's posture, applied here.
     */
    @Bean
    @Order(4)
    @Requirements({"GW_0127", "GW_0129"})
    public SecurityFilterChain machineApiChain(
            HttpSecurity http, MachineApiAuthenticationProvider machineApiAuthenticationProvider) throws Exception {
        AuthenticationManager authenticationManager = new ProviderManager(machineApiAuthenticationProvider);
        http.securityMatcher(new AndRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                        MachineApiAuthenticationFilter::presentsBearerCredential))
                // No CSRF token, for the same reason as the facade and publication chains: the
                // request is self-authenticating, STATELESS, no session is created and no cookie
                // is honoured -- the filter refuses a request that carries one at all. This chain
                // neither extends nor relies on the session chain's /api/** exemption.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(authenticationManager)
                .addFilterBefore(
                        new MachineApiAuthenticationFilter(authenticationManager), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(SecurityConfig::machineApiRules)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * One rule per reachable route, then {@code denyAll} (GW_0129). Deny-by-default is the shape:
     * an endpoint the registry does not classify as reachable is refused here, so a new endpoint
     * is unreachable until somebody names it rather than being admitted by silence.
     */
    @Requirements({"GW_0129"})
    private static void machineApiRules(
            org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>
                            .AuthorizationManagerRequestMatcherRegistry
                    registry) {
        for (java.util.Map.Entry<String, MachineApiRegistry.Route> reachable : MachineApiRegistry.reachableRoutes()) {
            MachineApiRegistry.Route route = reachable.getValue();
            registry.requestMatchers(PathPatternRequestMatcher.withDefaults()
                            .matcher(HttpMethod.valueOf(route.method()), route.pattern()))
                    .hasAuthority(MachineApiAuthentication.SCOPE_PREFIX + reachable.getKey());
        }
        registry.anyRequest().denyAll();
    }

    /**
     * OIDC for browsers; unauthenticated /api/** gets 401 instead of a login redirect.
     *
     * <p>With {@code skills-gateway.dev-insecure-auth=true} (development only, default off) the
     * web surface is open and requests act as the anonymous user "dev"; the git facade keeps
     * requiring PATs. GW_0011 holds for every default-configured deployment.
     */
    @Bean
    @Order(5)
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
