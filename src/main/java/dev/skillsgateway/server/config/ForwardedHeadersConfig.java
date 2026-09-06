package dev.skillsgateway.server.config;

import io.github.reqstool.annotations.Requirements;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Makes {@code server.forward-headers-strategy=framework} work on the native image (GW_0163).
 *
 * <p>Spring Boot registers its {@link ForwardedHeaderFilter} behind {@code @ConditionalOnProperty},
 * and a GraalVM native image evaluates that condition once, when the image is built. The property
 * is unset then, so the bean is never compiled in and no runtime setting can bring it back —
 * the documented remedy for a TLS-terminating proxy is silently inert on the released image.
 *
 * <p>Declaring the registration here, unconditionally, compiles it in on every packaging. Whether
 * it does anything is decided at runtime from the same property, so the operator-facing contract
 * stays Spring's own ({@code none}, {@code native}, {@code framework}) and means the same on the
 * JVM jar and the native image. Boot's own registration backs off when one exists
 * ({@code @ConditionalOnMissingFilterBean}), so the JVM never carries two. The {@code native}
 * strategy needs no help: Tomcat's {@code RemoteIpValve} is installed by a customizer that reads
 * the property at runtime.
 *
 * <p>The default stays off. The gateway cannot tell a proxy's header from a client's, so trusting
 * one is the operator's decision, made where the proxy is.
 *
 * <p>Conditional on being a servlet web application — the only condition that is safe here,
 * because it is a constant of this application (true at the native build and at every start)
 * rather than a property, and because a context without a web server has no {@code
 * ServerProperties} to read.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForwardedHeadersConfig {

    @Bean
    @Requirements({"GW_0163"})
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(ServerProperties serverProperties) {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        // Where Boot places it: ahead of everything, on every dispatch that can build a URL.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(
                serverProperties.getForwardHeadersStrategy() == ServerProperties.ForwardHeadersStrategy.FRAMEWORK);
        return registration;
    }
}
