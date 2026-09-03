package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The address half of GW_0157, exercised as a pure function over {@link InetAddress}es so that
 * every class of forbidden target can be stated without a network.
 *
 * <p>Two tiers, and the split is the point. The always-refused tier is what no configuration may
 * unlock — a cloud metadata endpoint answers link-local and answers with credentials, so a
 * deployment that legitimately needs loopback for a development topology must not thereby also
 * reach it. The private tier is what an operator may permit deliberately.
 */
class SourceAddressPolicyTests {

    private static final SourceAddressPolicy STRICT = new SourceAddressPolicy(false);
    private static final SourceAddressPolicy PRIVATE_ALLOWED = new SourceAddressPolicy(true);

    private static InetAddress at(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void the_cloud_metadata_endpoint_is_refused_however_the_gateway_is_configured() throws Exception {
        assertThat(STRICT.refuse(at("169.254.169.254"))).contains("link-local");
        assertThat(PRIVATE_ALLOWED.refuse(at("169.254.169.254"))).contains("link-local");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void ipv6_link_local_is_refused_however_the_gateway_is_configured() throws Exception {
        assertThat(STRICT.refuse(at("fe80::1"))).contains("link-local");
        assertThat(PRIVATE_ALLOWED.refuse(at("fe80::1"))).contains("link-local");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void an_ipv4_mapped_forbidden_address_is_refused_as_the_address_it_encodes() throws Exception {
        // ::ffff:169.254.169.254 is the metadata endpoint wearing an IPv6 costume. Whether the
        // runtime hands it back as a v4 or a v6 address, the v4 rules have to reach it.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) 169;
        mapped[13] = (byte) 254;
        mapped[14] = (byte) 169;
        mapped[15] = (byte) 254;
        assertThat(PRIVATE_ALLOWED.refuse(InetAddress.getByAddress("metadata.invalid", mapped)))
                .contains("link-local");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void an_ipv4_compatible_forbidden_address_is_refused_as_the_address_it_encodes() throws Exception {
        // ::a.b.c.d, the deprecated compatible form, is the same evasion with a different prefix.
        byte[] compatible = new byte[16];
        compatible[12] = (byte) 169;
        compatible[13] = (byte) 254;
        compatible[14] = (byte) 169;
        compatible[15] = (byte) 254;
        assertThat(PRIVATE_ALLOWED.refuse(InetAddress.getByAddress("metadata.invalid", compatible)))
                .contains("link-local");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void loopback_is_refused_by_default_and_permitted_only_deliberately() throws Exception {
        assertThat(STRICT.refuse(at("127.0.0.1"))).contains("loopback");
        assertThat(STRICT.refuse(at("::1"))).contains("loopback");
        assertThat(PRIVATE_ALLOWED.refuse(at("127.0.0.1"))).isNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("::1"))).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void rfc1918_addresses_are_refused_by_default() throws Exception {
        assertThat(STRICT.refuse(at("10.0.0.1"))).contains("private");
        assertThat(STRICT.refuse(at("172.16.0.1"))).contains("private");
        assertThat(STRICT.refuse(at("192.168.1.1"))).contains("private");
        assertThat(PRIVATE_ALLOWED.refuse(at("10.0.0.1"))).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void carrier_grade_nat_and_unique_local_are_refused_by_default() throws Exception {
        assertThat(STRICT.refuse(at("100.64.0.1"))).contains("private");
        assertThat(STRICT.refuse(at("fc00::1"))).contains("private");
        assertThat(STRICT.refuse(at("fd12:3456::1"))).contains("private");
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void multicast_unspecified_broadcast_and_reserved_are_always_refused() throws Exception {
        assertThat(PRIVATE_ALLOWED.refuse(at("224.0.0.1"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("ff02::1"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("0.0.0.0"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("::"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("0.1.2.3"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("255.255.255.255"))).isNotNull();
        assertThat(PRIVATE_ALLOWED.refuse(at("240.0.0.1"))).isNotNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_public_address_is_permitted() throws Exception {
        assertThat(STRICT.refuse(at("140.82.121.4"))).isNull();
        assertThat(STRICT.refuse(at("2606:4700::1"))).isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_host_resolving_to_both_a_public_and_a_private_address_is_refused_as_a_whole() throws Exception {
        // The evasion this closes: resolve to one public address the check passes on and one
        // private address the connection could then be made to. Every address is checked.
        List<InetAddress> mixed = List.of(at("140.82.121.4"), at("10.1.2.3"));

        assertThat(STRICT.refuseAny("mixed.invalid", mixed)).isNotNull().contains("private");
        assertThat(STRICT.refuseAny("public.invalid", List.of(at("140.82.121.4"))))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0157"})
    void a_host_that_resolves_to_nothing_is_refused_rather_than_passed_through() {
        assertThat(STRICT.refuseAny("nothing.invalid", List.of())).isNotNull();
    }
}
