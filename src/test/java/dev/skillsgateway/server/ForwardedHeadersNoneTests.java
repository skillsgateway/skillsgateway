package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The strategy explicitly disabled: the headers are ignored (GW_AUTH_0029).
 *
 * <p>This posture looks redundant beside the unset one and is not. Unset and {@code none} agreeing
 * is what separates "the strategy was read and said no" from "nothing ever read the strategy" — the
 * second being exactly the defect this requirement exists to close, which on the released image was
 * indistinguishable from a working {@code framework} until all the off-postures were compared
 * against each other.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=none")
class ForwardedHeadersNoneTests extends AbstractForwardedHeadersTest {

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_headers_are_ignored_when_the_strategy_is_disabled() {
        assertThat(redirectUriSeenByTheIdp(Map.of())).isEqualTo("http://localhost:" + port + LOGIN_PATH);
        assertThat(redirectUriSeenByTheIdp(forwardedByTheProxy())).isEqualTo("http://localhost:" + port + LOGIN_PATH);
    }
}
