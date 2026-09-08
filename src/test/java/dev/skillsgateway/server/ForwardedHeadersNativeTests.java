package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Tomcat's {@code RemoteIpValve} (GW_AUTH_0029), which honours the headers only from a peer inside its
 * internal-proxy ranges; the loopback address this test connects from is one of them.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=native")
class ForwardedHeadersNativeTests extends AbstractForwardedHeadersTest {

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_forwarded_scheme_and_host_are_honoured() {
        assertThat(redirectUriSeenByTheIdp(forwardedByTheProxy())).isEqualTo("https://" + PUBLIC_HOST + LOGIN_PATH);
    }
}
