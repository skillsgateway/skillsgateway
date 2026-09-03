package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The URL half of GW_0157: what the gateway will ask for, and what a redirect may change, decided
 * without a connection.
 *
 * <p>An address literal spelled in decimal, octal or hexadecimal exists for exactly one reason — to
 * mean one thing to a filter and another to a resolver — so those spellings are refused as
 * ambiguous rather than resolved and argued about. Everything else here is about redirects, which
 * are the one part of a fetch whose target the gateway does not choose.
 */
class SourceUrlPolicyTests {

    private static final SourceUrlPolicy HTTPS_ONLY = new SourceUrlPolicy(Set.of("https"), 3);
    private static final SourceUrlPolicy HTTP_TOO = new SourceUrlPolicy(Set.of("http", "https"), 3);

    @Test
    @SVCs({"SVC_GW_0157"})
    void an_ordinary_https_url_is_permitted() {
        assertThat(HTTPS_ONLY.refuseTarget("https://github.com/acme/tools")).isNull();
        assertThat(HTTPS_ONLY.refuseTarget("https://ghe.example.com:8443/acme/tools"))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_scheme_outside_the_allowlist_is_refused() {
        assertThat(HTTPS_ONLY.refuseTarget("http://github.com/acme/tools")).contains("scheme");
        assertThat(HTTPS_ONLY.refuseTarget("file:///etc/passwd")).contains("scheme");
        assertThat(HTTPS_ONLY.refuseTarget("gopher://github.com/x")).contains("scheme");
        assertThat(HTTPS_ONLY.refuseTarget("github.com/acme/tools")).isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void embedded_credentials_are_refused() {
        // Not a fussy rule: credentials in a redirect target are how a fetch is made to
        // authenticate to something the first hop was never checked against.
        assertThat(HTTPS_ONLY.refuseTarget("https://user:pw@github.com/acme/tools"))
                .contains("credentials");
        assertThat(HTTPS_ONLY.refuseTarget("https://user@github.com/acme/tools"))
                .contains("credentials");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_decimal_address_literal_is_refused() {
        assertThat(HTTP_TOO.refuseTarget("http://2852039166/x")).contains("ambiguous");
        assertThat(HTTP_TOO.refuseTarget("http://2130706433/x")).contains("ambiguous");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void an_octal_address_literal_is_refused() {
        assertThat(HTTP_TOO.refuseTarget("http://0300.0250.0.1/x")).contains("ambiguous");
        assertThat(HTTP_TOO.refuseTarget("http://0177.0.0.1/x")).contains("ambiguous");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_hexadecimal_address_literal_is_refused() {
        assertThat(HTTP_TOO.refuseTarget("http://0xA9FEA9FE/x")).contains("ambiguous");
        assertThat(HTTP_TOO.refuseTarget("http://0x7f.1/x")).contains("ambiguous");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_short_dotted_address_literal_is_refused() {
        // inet_aton reads 127.1 as 127.0.0.1; a reader does not.
        assertThat(HTTP_TOO.refuseTarget("http://127.1/x")).contains("ambiguous");
        assertThat(HTTP_TOO.refuseTarget("http://169.254.43518/x")).contains("ambiguous");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_plain_dotted_quad_and_a_bracketed_ipv6_literal_are_not_ambiguous() {
        // They are unambiguous, so the address policy — not this one — decides them.
        assertThat(HTTP_TOO.refuseTarget("http://140.82.121.4/x")).isNull();
        assertThat(HTTP_TOO.refuseTarget("http://[2606:4700::1]/x")).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_that_stays_on_the_same_origin_is_permitted() {
        assertThat(HTTPS_ONLY.refuseRedirect(
                        "https://github.com/acme/tools/info/refs", "https://github.com/acme/tools.git/info/refs", 1))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_that_leaves_the_host_is_refused() {
        assertThat(HTTPS_ONLY.refuseRedirect("https://github.com/acme/tools", "https://evil.example.com/x", 1))
                .contains("host");
        // The near-miss the host allowlist is also written against.
        assertThat(HTTPS_ONLY.refuseRedirect("https://github.com/acme/tools", "https://evil-github.com/x", 1))
                .contains("host");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_to_the_cloud_metadata_endpoint_is_refused_on_the_host_rule_alone() {
        // Refused here as well as by the address policy: the target is never contacted, so the
        // refusal does not depend on resolving it.
        assertThat(HTTP_TOO.refuseRedirect(
                        "http://github.com/acme/tools", "http://169.254.169.254/latest/meta-data/", 1))
                .isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_scheme_downgrade_is_refused() {
        assertThat(HTTP_TOO.refuseRedirect("https://github.com/acme/tools", "http://github.com/acme/tools", 1))
                .contains("downgrade");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_to_another_port_on_the_same_host_is_refused() {
        assertThat(HTTP_TOO.refuseRedirect("http://ghe.example.com:8080/x", "http://ghe.example.com:9090/x", 1))
                .contains("port");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_chain_longer_than_the_maximum_is_refused() {
        String from = "https://github.com/acme/tools";
        String to = "https://github.com/acme/tools.git";

        assertThat(HTTPS_ONLY.refuseRedirect(from, to, 3)).isNull();
        assertThat(HTTPS_ONLY.refuseRedirect(from, to, 4)).contains("redirect");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_redirect_with_no_target_is_refused_rather_than_ignored() {
        assertThat(HTTPS_ONLY.refuseRedirect("https://github.com/acme/tools", null, 1))
                .isNotNull();
        assertThat(HTTPS_ONLY.refuseRedirect("https://github.com/acme/tools", "", 1))
                .isNotNull();
    }
}
