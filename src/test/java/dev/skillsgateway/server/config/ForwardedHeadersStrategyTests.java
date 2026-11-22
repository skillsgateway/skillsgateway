package dev.skillsgateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.server.ForwardedRequestHeaders;
import org.springframework.boot.web.server.autoconfigure.ForwardedHeaderStrategy;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * The Boot 4.2 spelling of the forwarded-header setting must switch the gateway's own filter on, as
 * the deprecated one does (GW_AUTH_0029). Boot's registration backs off because this one always
 * exists, so a strategy read only from the old property would leave the new one silently inert.
 *
 * <p>Plain unit tests: the end-to-end behaviour per strategy is covered by the {@code
 * ForwardedHeaders*Tests} contexts, and a context per spelling would cost the context budget.
 */
class ForwardedHeadersStrategyTests {

    private final ForwardedHeadersConfig config = new ForwardedHeadersConfig();

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_boot_4_2_property_enables_the_filter() {
        ServerProperties server = new ServerProperties();
        server.getRequest().getHeader().getForwarded().setStrategy(ForwardedHeaderStrategy.FRAMEWORK);

        assertThat(registration(server).isEnabled()).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    @SuppressWarnings("removal")
    void the_deprecated_property_still_enables_the_filter() {
        ServerProperties server = new ServerProperties();
        server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.FRAMEWORK);

        assertThat(registration(server).isEnabled()).isTrue();
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void unset_web_server_and_none_leave_the_filter_off() {
        assertThat(registration(new ServerProperties()).isEnabled()).isFalse();
        for (ForwardedHeaderStrategy strategy :
                new ForwardedHeaderStrategy[] {ForwardedHeaderStrategy.WEB_SERVER, ForwardedHeaderStrategy.NONE}) {
            ServerProperties server = new ServerProperties();
            server.getRequest().getHeader().getForwarded().setStrategy(strategy);
            assertThat(registration(server).isEnabled()).as(strategy.name()).isFalse();
        }
    }

    /** Boot 4.2's default: X-Forwarded-* believed, X-Forwarded-Prefix not, unless asked for. */
    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void x_forwarded_prefix_is_ignored_unless_use_prefix_is_set() throws Exception {
        ServerProperties server = new ServerProperties();
        assertThat(contextPathSeen(registration(server).getFilter())).isEmpty();

        server.getRequest().getHeader().getForwarded().setUsePrefix(true);
        assertThat(contextPathSeen(registration(server).getFilter())).isEqualTo("/gateway");
    }

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void rfc_7239_naming_reads_the_standard_header_and_not_x_forwarded() throws Exception {
        ServerProperties server = new ServerProperties();
        server.getRequest().getHeader().getForwarded().setNaming(ForwardedRequestHeaders.Naming.RFC_7239);
        ForwardedHeaderFilter filter = registration(server).getFilter();

        MockHttpServletRequest standard = new MockHttpServletRequest("GET", "/login");
        standard.addHeader("Forwarded", "proto=https;host=gateway.example");
        assertThat(urlSeen(filter, standard)).isEqualTo("https://gateway.example/login");

        MockHttpServletRequest legacy = new MockHttpServletRequest("GET", "/login");
        legacy.addHeader("X-Forwarded-Proto", "https");
        legacy.addHeader("X-Forwarded-Host", "gateway.example");
        assertThat(urlSeen(filter, legacy)).isEqualTo("http://localhost/login");
    }

    private FilterRegistrationBean<ForwardedHeaderFilter> registration(ServerProperties server) {
        return config.forwardedHeaderFilter(
                server,
                new TomcatServerProperties(),
                new StaticListableBeanFactory().getBeanProvider(ForwardedHeaderFilterCustomizer.class));
    }

    private static String contextPathSeen(ForwardedHeaderFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.addHeader("X-Forwarded-Prefix", "/gateway");
        String[] seen = new String[1];
        filter.doFilter(
                request,
                new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> seen[0] = ((jakarta.servlet.http.HttpServletRequest) req).getContextPath());
        return seen[0];
    }

    private static String urlSeen(ForwardedHeaderFilter filter, MockHttpServletRequest request) throws Exception {
        String[] seen = new String[1];
        filter.doFilter(
                request,
                new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> seen[0] = ((jakarta.servlet.http.HttpServletRequest) req)
                        .getRequestURL()
                        .toString());
        return seen[0];
    }
}
