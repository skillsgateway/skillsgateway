package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** No strategy configured: the headers are ignored, whoever sent them (GW_0163). */
class ForwardedHeadersUnsetTests extends AbstractForwardedHeadersTest {

    @Test
    @SVCs({"SVC_GW_0163"})
    void the_headers_are_ignored_by_default() {
        assertThat(redirectUriSeenByTheIdp(Map.of())).isEqualTo("http://localhost:" + port + LOGIN_PATH);
        assertThat(redirectUriSeenByTheIdp(forwardedByTheProxy())).isEqualTo("http://localhost:" + port + LOGIN_PATH);
    }
}
