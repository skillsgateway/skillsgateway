package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Spring's filter, which is the strategy the native image used to lose (GW_AUTH_0029).
 *
 * <p>Set by the deprecated {@code server.forward-headers-strategy}, deliberately: the other postures
 * use the Boot 4.2 name, and this is the one context that proves the gateway's own registration
 * still honours the old name end to end, as a deployment configured before the rename relies on.
 * Move it to {@code server.request.header.forwarded.strategy} with the upgrade to Spring Boot 4.4,
 * which removes the old name ({@code ForwardedHeadersStrategyTests} covers both at unit level).
 */
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedHeadersFrameworkTests extends AbstractForwardedHeadersTest {

    @Autowired
    ApplicationContext context;

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_forwarded_scheme_and_host_are_honoured() {
        assertThat(redirectUriSeenByTheIdp(forwardedByTheProxy())).isEqualTo("https://" + PUBLIC_HOST + LOGIN_PATH);
    }

    /** Boot's own registration must back off, or the JVM would run the filter twice. */
    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void exactly_one_forwarded_header_filter_is_registered() {
        long registrations = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ForwardedHeaderFilter)
                .count();
        assertThat(registrations).isEqualTo(1);
    }
}
