package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.config.ForwardedHeadersConfig;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Relative redirects follow {@code server.tomcat.use-relative-redirects} at runtime (GW_AUTH_0029).
 *
 * <p>Boot applies this from a second bean, {@code tomcatForwardedHeaderFilterCustomizer}, which
 * carries the same build-time condition as the filter and is therefore absent from the native image
 * alongside it. Declaring the registration in {@link ForwardedHeadersConfig} means inheriting what
 * that bean did, and a constant in its place would freeze at build time a value Boot resolves at
 * runtime — the defect this requirement exists to close, one layer down, and quieter: it produces
 * subtly wrong {@code Location} headers rather than a login that fails.
 *
 * <p>Asserting the filter's own state rather than an emitted redirect is deliberate. The property
 * governs redirects Tomcat produces for the application's own paths, which the forwarded-headers
 * suite's probe — an absolute authorization redirect to the identity provider — cannot show. A
 * wiring assertion that fails when someone writes a literal is worth more here than a behavioural
 * one that cannot reach the behaviour.
 *
 * <p>And because the claim is entirely about how one bean reads one property, it is made against a
 * {@link WebApplicationContextRunner} over that configuration rather than against a booted gateway:
 * the registration under test is the same object either way, and the posture cost a whole
 * application context to observe (#305). That the application does register this bean is asserted
 * where it belongs, on a real server, by {@link ForwardedHeadersFrameworkTests}. The runner is a
 * <em>web</em> one because {@link ForwardedHeadersConfig} is conditional on a servlet application,
 * which is the condition it deliberately keeps.
 */
class ForwardedHeadersRelativeRedirectsTests {

    private final WebApplicationContextRunner contexts =
            new WebApplicationContextRunner().withUserConfiguration(RegistrationUnderTest.class);

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_filter_takes_relative_redirects_from_the_property() {
        contexts.withPropertyValues("server.tomcat.use-relative-redirects=true")
                .run(context -> assertThat(relativeRedirects(context)).isEqualTo(true));

        // Boot's default is false, so a literal would not survive this pair: whichever value were
        // written in, one of the two runs would read the other.
        contexts.run(context -> assertThat(relativeRedirects(context)).isEqualTo(false));
    }

    private static Object relativeRedirects(ApplicationContext context) {
        Object filter = context.getBean(FilterRegistrationBean.class).getFilter();
        assertThat(filter).isInstanceOf(ForwardedHeaderFilter.class);
        return ReflectionTestUtils.getField(filter, "relativeRedirects");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ServerProperties.class, TomcatServerProperties.class})
    @Import(ForwardedHeadersConfig.class)
    static class RegistrationUnderTest {}
}
