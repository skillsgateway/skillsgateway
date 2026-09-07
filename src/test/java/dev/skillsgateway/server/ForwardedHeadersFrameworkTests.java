package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.ForwardedHeaderFilter;

/** Spring's filter, which is the strategy the native image used to lose (GW_0163). */
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedHeadersFrameworkTests extends AbstractForwardedHeadersTest {

    @Autowired
    ApplicationContext context;

    @Test
    @SVCs({"SVC_GW_0163"})
    void the_forwarded_scheme_and_host_are_honoured() {
        assertThat(redirectUriSeenByTheIdp(forwardedByTheProxy())).isEqualTo("https://" + PUBLIC_HOST + LOGIN_PATH);
    }

    /** Boot's own registration must back off, or the JVM would run the filter twice. */
    @Test
    @SVCs({"SVC_GW_0163"})
    void exactly_one_forwarded_header_filter_is_registered() {
        long registrations = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ForwardedHeaderFilter)
                .count();
        assertThat(registrations).isEqualTo(1);
    }
}
