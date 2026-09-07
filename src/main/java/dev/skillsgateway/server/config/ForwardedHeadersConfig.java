package dev.skillsgateway.server.config;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Makes {@code server.forward-headers-strategy=framework} mean the same thing on every
 * packaging (GW_0163).
 *
 * <p>Spring Boot registers its {@link ForwardedHeaderFilter} behind {@code @ConditionalOnProperty},
 * and an ahead-of-time image evaluates that condition once, when the image is built. The property
 * is unset then, so the bean is never compiled in and no runtime setting can bring it back — which
 * is how the documented remedy for a TLS-terminating proxy came to be silently inert on the GraalVM
 * native image the release used to publish (#272).
 *
 * <p>Declaring the registration here, unconditionally, compiles it in on every packaging. Whether
 * it does anything is decided at runtime from the same property, so the operator-facing contract
 * stays Spring's own ({@code none}, {@code native}, {@code framework}) and does not depend on how
 * the artifact was built. The released artifact is a JVM container now (ADR 0012), where the
 * condition would have been evaluated at runtime anyway; this stays because the property of not
 * depending on that is worth more than the line it costs. Boot's own registration backs off when one
 * exists
 * ({@code @ConditionalOnMissingFilterBean}), so the JVM never carries two. The {@code native}
 * strategy needs no help: Tomcat's {@code RemoteIpValve} is installed by a customizer that reads
 * the property at runtime.
 *
 * <p>The default stays off. The gateway cannot tell a proxy's header from a client's, so trusting
 * one is the operator's decision, made where the proxy is.
 *
 * <p>Conditional on being a servlet web application — the only condition that is safe here,
 * because it is a constant of this application (true whenever it is built and at every start)
 * rather than a property, and because a context without a web server has no {@code
 * ServerProperties} to read.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForwardedHeadersConfig {

    @Bean
    @Requirements({"GW_0163"})
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(
            ServerProperties serverProperties,
            TomcatServerProperties tomcatServerProperties,
            ObjectProvider<ForwardedHeaderFilterCustomizer> customizers) {
        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        // Boot applies this from a *second* bean, tomcatForwardedHeaderFilterCustomizer, which
        // carries the same @ConditionalOnProperty and would be frozen out alongside the filter by
        // anything that evaluates it early. Taking over a bean from auto-configuration means
        // inheriting everything the auto-configuration did to it, including the parts expressed
        // elsewhere.
        // Read at runtime, never written as a literal: a constant here would freeze at build time
        // a value Boot resolves at runtime -- this very defect one layer down, surfacing as subtly
        // wrong Location headers rather than a failed login.
        filter.setRelativeRedirects(tomcatServerProperties.isUseRelativeRedirects());
        // Boot's documented extension point, kept working now that its registration is ours.
        customizers.orderedStream().forEach(customizer -> customizer.customize(filter));

        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        // Where Boot places it: ahead of everything, on every dispatch that can build a URL.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(
                serverProperties.getForwardHeadersStrategy() == ServerProperties.ForwardHeadersStrategy.FRAMEWORK);
        return registration;
    }
}
