package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Relative redirects follow {@code server.tomcat.use-relative-redirects} at runtime (GW_AUTH_0029).
 *
 * <p>Boot applies this from a second bean, {@code tomcatForwardedHeaderFilterCustomizer}, which
 * carries the same build-time condition as the filter and is therefore absent from the native image
 * alongside it. Declaring the registration here means inheriting what that bean did, and a constant
 * in its place would freeze at build time a value Boot resolves at runtime — the defect this
 * requirement exists to close, one layer down, and quieter: it produces subtly wrong {@code
 * Location} headers rather than a login that fails.
 *
 * <p>Asserting the filter's own state rather than an emitted redirect is deliberate. The property
 * governs redirects Tomcat produces for the application's own paths, which this suite's probe — an
 * absolute authorization redirect to the identity provider — cannot show. A wiring assertion that
 * fails when someone writes a literal is worth more here than a behavioural one that cannot reach
 * the behaviour.
 */
@TestPropertySource(
        properties = {"server.forward-headers-strategy=framework", "server.tomcat.use-relative-redirects=true"})
class ForwardedHeadersRelativeRedirectsTests extends AbstractForwardedHeadersTest {

    @Autowired
    FilterRegistrationBean<ForwardedHeaderFilter> registration;

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_filter_takes_relative_redirects_from_the_property() {
        // Boot's default is false, so a literal would not survive this.
        assertThat(ReflectionTestUtils.getField(registration.getFilter(), "relativeRedirects"))
                .isEqualTo(true);
    }
}
