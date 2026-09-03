package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Which addresses the gateway will contact while resolving an external plugin source (GW_0157).
 *
 * <p>Manifest content decides where resolution sends requests, and an internal address answering a
 * request that appears to originate inside the perimeter is the whole of server-side request
 * forgery. This is the second layer behind network egress isolation (ADR 0011 §3), not the primary
 * control — but it is the layer the gateway can actually enforce.
 *
 * <p>Two tiers, and the split is load-bearing rather than tidy:
 *
 * <ul>
 *   <li><b>Always refused</b>, whatever the configuration says: link-local, multicast, unspecified,
 *       broadcast, {@code 0.0.0.0/8} and the reserved {@code 240.0.0.0/4}. A cloud metadata endpoint
 *       is link-local and answers with credentials, so a deployment that legitimately needs loopback
 *       must not be able to unlock it as a side effect.
 *   <li><b>Refused unless private networks are permitted</b>: loopback, RFC1918, carrier-grade NAT
 *       and IPv6 unique-local. These are a real development and test topology, so they are an
 *       operator's deliberate choice — default off.
 * </ul>
 *
 * <p>Every check is applied to the address the runtime resolved, never to host text: a decimal,
 * octal or IPv4-mapped spelling of a forbidden address is still that address by the time it gets
 * here. {@link #refuseAny} exists because checking one address is not enough — a name that resolves
 * to one public and one private address must be refused as a whole, or the connection can be made
 * to the address the check did not look at.
 */
public record SourceAddressPolicy(boolean allowPrivateNetworks) {

    /** Returns why the address is forbidden, or {@code null} when it may be contacted. */
    @Requirements({"GW_0157"})
    public String refuse(InetAddress address) {
        if (address == null) {
            return "an address that could not be determined";
        }
        InetAddress unwrapped = unwrapIpv4(address);
        byte[] bytes = unwrapped.getAddress();
        if (unwrapped.isAnyLocalAddress()) {
            return "the unspecified address " + unwrapped.getHostAddress();
        }
        if (unwrapped.isLinkLocalAddress()) {
            return "the link-local address " + unwrapped.getHostAddress();
        }
        if (unwrapped.isMulticastAddress()) {
            return "the multicast address " + unwrapped.getHostAddress();
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            if (first == 0) {
                return "the reserved address " + unwrapped.getHostAddress();
            }
            if (first >= 240) {
                // 240.0.0.0/4, which includes the 255.255.255.255 broadcast address.
                return "the reserved address " + unwrapped.getHostAddress();
            }
        }
        String privateReason = privateReason(unwrapped, bytes);
        if (privateReason != null) {
            return allowPrivateNetworks ? null : privateReason;
        }
        return null;
    }

    /**
     * Returns why the host may not be contacted, or {@code null} when every address it resolved to
     * is permitted. A host that resolved to nothing is refused: there is no address to validate, so
     * there is nothing to be confident about.
     */
    @Requirements({"GW_0157"})
    public String refuseAny(String host, List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return "host '%s' resolved to no address".formatted(host);
        }
        for (InetAddress address : addresses) {
            String reason = refuse(address);
            if (reason != null) {
                return "host '%s' resolves to %s".formatted(host, reason);
            }
        }
        return null;
    }

    /** Resolves the host and refuses it in one step; a resolution failure is a refusal. */
    @Requirements({"GW_0157"})
    public Resolution resolve(String host) {
        try {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
            String reason = refuseAny(host, addresses);
            return reason == null ? new Resolution(addresses, null) : new Resolution(List.of(), reason);
        } catch (UnknownHostException e) {
            return new Resolution(List.of(), "host '%s' could not be resolved".formatted(host));
        }
    }

    /**
     * The addresses a host resolved to and passed the policy on, or the reason it did not. The
     * addresses are carried out so the caller connects to one of them rather than resolving the
     * name a second time — a name that resolved publicly during validation must not be able to
     * resolve privately at connect time.
     */
    public record Resolution(List<InetAddress> addresses, String violation) {

        public boolean permitted() {
            return violation == null && !addresses.isEmpty();
        }

        public InetAddress first() {
            return addresses.getFirst();
        }
    }

    private static String privateReason(InetAddress address, byte[] bytes) {
        if (address.isLoopbackAddress()) {
            return "the loopback address " + address.getHostAddress();
        }
        if (address.isSiteLocalAddress()) {
            return "the private address " + address.getHostAddress();
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10, carrier-grade NAT: routable-looking, and not the public internet.
            if (first == 100 && second >= 64 && second <= 127) {
                return "the private address " + address.getHostAddress();
            }
            return null;
        }
        // fc00::/7, unique-local. Inet6Address.isSiteLocalAddress only knows the deprecated
        // fec0::/10, so the current form has to be named here.
        if ((bytes[0] & 0xFE) == 0xFC) {
            return "the private address " + address.getHostAddress();
        }
        return null;
    }

    /**
     * An IPv4 address written as an IPv6 one — {@code ::ffff:a.b.c.d} (mapped) or {@code ::a.b.c.d}
     * (the deprecated compatible form) — reduced to the address it encodes, so the IPv4 rules reach
     * it. Runtimes differ on whether they hand these back as v4 or v6, which is exactly why this
     * does not depend on which.
     */
    private static InetAddress unwrapIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        boolean compatible = bytes[10] == 0 && bytes[11] == 0;
        if (!mapped && !compatible) {
            return address;
        }
        byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        if (compatible && v4[0] == 0 && v4[1] == 0 && v4[2] == 0 && (v4[3] == 0 || v4[3] == 1)) {
            // :: and ::1 are the unspecified and loopback addresses, not compatible-form v4.
            return address;
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException impossible) {
            return address;
        }
    }
}
