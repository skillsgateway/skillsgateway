package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The escape hatch that trusts no header at all: the registered URI, stated outright (GW_AUTH_0029). */
@TestPropertySource(properties = "SGW_OIDC_REDIRECT_URI=https://gateway.example.com/login/oauth2/code/idp")
class ForwardedHeadersRedirectUriTests extends AbstractForwardedHeadersTest {

    @Test
    @SVCs({"SVC_GW_AUTH_0029"})
    void the_stated_uri_is_used_whatever_the_request_says() {
        assertThat(redirectUriSeenByTheIdp(Map.of())).isEqualTo("https://" + PUBLIC_HOST + LOGIN_PATH);
        assertThat(redirectUriSeenByTheIdp(Map.of("X-Forwarded-Proto", "http", "X-Forwarded-Host", "evil")))
                .isEqualTo("https://" + PUBLIC_HOST + LOGIN_PATH);
    }
}
