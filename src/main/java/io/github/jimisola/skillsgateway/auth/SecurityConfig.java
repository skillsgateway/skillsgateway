package io.github.jimisola.skillsgateway.auth;

import io.github.jimisola.skillsgateway.config.SkillsGatewayProperties;
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * OIDC for browsers; unauthenticated /api/** gets 401 instead of a login redirect.
     *
     * <p>With {@code skills-gateway.dev-insecure-auth=true} (development only, default off) the
     * web surface is open and requests act as the anonymous user "dev"; the git facade keeps
     * requiring PATs. GW_0011 holds for every default-configured deployment.
     */
    @Bean
    @Order(2)
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
