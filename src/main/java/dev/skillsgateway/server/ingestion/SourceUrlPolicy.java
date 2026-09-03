package dev.skillsgateway.server.ingestion;

import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * What the gateway will ask for while resolving an external plugin source, and what a redirect may
 * change (GW_0157). Decided from the URL alone — no resolution, no connection — so a refusal costs
 * nothing and cannot itself cause traffic.
 *
 * <p>Host text is never treated as an address here. An address literal spelled in decimal, octal or
 * hexadecimal exists for one purpose, which is to mean one thing to whatever inspects the string
 * and another to whatever resolves it, so those spellings are refused as <em>ambiguous</em> rather
 * than normalised and argued about. A plain dotted quad or a bracketed IPv6 literal is unambiguous
 * and is left to {@link SourceAddressPolicy}, which is the component that should be deciding
 * addresses.
 *
 * <p>Redirects are the one part of a fetch whose target the gateway does not choose, so they are
 * pinned to the origin: same host, same port, no downgrade, and a bounded chain. That makes the
 * redirect worthless as a way out of the host allowlist rather than merely inconvenient.
 *
 * @param allowedSchemes {@code skills-gateway.allowed-url-schemes}, the one scheme policy for every
 *     URL the gateway dereferences
 * @param maxRedirects how many redirect hops one fetch may take
 */
public record SourceUrlPolicy(Set<String> allowedSchemes, int maxRedirects) {

    public SourceUrlPolicy {
        allowedSchemes = allowedSchemes == null ? Set.of() : Set.copyOf(allowedSchemes);
    }

    /** Returns why this URL may not be requested, or {@code null} when it may. */
    @Requirements({"GW_0157"})
    public String refuseTarget(String url) {
        Parsed parsed = Parsed.of(url);
        // The scheme is decided before anything else, even when the rest of the URL did not parse:
        // it is the check an operator configured, so "scheme is not allowed" is the message that
        // tells them something they can act on. A file: URL has no authority at all, and reporting
        // it as a missing host would be true and useless.
        if (parsed.scheme() != null && !allowedSchemes.contains(parsed.scheme())) {
            return "URL scheme '%s' is not allowed".formatted(parsed.scheme());
        }
        if (parsed.failure() != null) {
            return parsed.failure();
        }
        if (parsed.credentials()) {
            return "a URL carrying embedded credentials";
        }
        String ambiguous = ambiguity(parsed.host());
        if (ambiguous != null) {
            return ambiguous;
        }
        return null;
    }

    /**
     * Returns why this redirect may not be followed, or {@code null} when it may.
     *
     * @param hop which hop this is, counting from one
     */
    @Requirements({"GW_0157"})
    public String refuseRedirect(String from, String to, int hop) {
        if (hop > maxRedirects) {
            return "more than the permitted %d redirect hops".formatted(maxRedirects);
        }
        if (to == null || to.isBlank()) {
            return "a redirect with no target";
        }
        String refusal = refuseTarget(to);
        if (refusal != null) {
            return "a redirect to " + refusal;
        }
        Parsed origin = Parsed.of(from);
        Parsed target = Parsed.of(to);
        if (origin.failure() != null) {
            return origin.failure();
        }
        if (!origin.host().equals(target.host())) {
            return "a redirect that leaves the host '%s' for '%s'".formatted(origin.host(), target.host());
        }
        if ("https".equals(origin.scheme()) && !"https".equals(target.scheme())) {
            return "a redirect that downgrades from https to '%s'".formatted(target.scheme());
        }
        if (origin.port() != target.port()) {
            return "a redirect that changes the port from %d to %d".formatted(origin.port(), target.port());
        }
        return null;
    }

    /** Whether a host text is an address literal spelled in a way that only a resolver reads. */
    private static String ambiguity(String host) {
        if (host.startsWith("[")) {
            return null;
        }
        String[] parts = host.split("\\.", -1);
        boolean numericOnly = true;
        for (String part : parts) {
            if (part.startsWith("0x")) {
                return "an ambiguous address literal '%s'".formatted(host);
            }
            if (part.length() > 1 && part.charAt(0) == '0' && digitsOnly(part)) {
                return "an ambiguous address literal '%s'".formatted(host);
            }
            if (!digitsOnly(part)) {
                numericOnly = false;
            }
        }
        if (!numericOnly) {
            return null;
        }
        // Every component is a decimal number, so this is an address rather than a name — and
        // inet_aton reads a form with fewer than four of them (127.1) as an address a reader does
        // not. Only the canonical dotted quad is accepted; the address policy decides it.
        if (parts.length != 4) {
            return "an ambiguous address literal '%s'".formatted(host);
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) {
                return "an ambiguous address literal '%s'".formatted(host);
            }
        }
        return null;
    }

    private static boolean digitsOnly(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * A URL split into the three things the policy decides on. The authority is taken apart by hand
     * rather than through {@code URI.getHost()}, which answers {@code null} for exactly the hosts
     * this policy most needs to see — a registry-based authority such as {@code 0xA9FEA9FE} is not
     * a hostname by RFC 2396, and a component that reads the host as absent cannot refuse it.
     */
    private record Parsed(String scheme, String host, int port, boolean credentials, String failure) {

        private static Parsed failed(String reason) {
            return new Parsed(null, null, -1, false, reason);
        }

        /** As {@link #failed(String)}, keeping the scheme so the scheme check can still run. */
        private static Parsed failed(String scheme, String reason) {
            return new Parsed(scheme, null, -1, false, reason);
        }

        static Parsed of(String url) {
            if (url == null || url.isBlank()) {
                return failed("an empty URL");
            }
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                return failed("a URL that is not a valid URI");
            }
            if (uri.getScheme() == null) {
                return failed("a URL with no scheme");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return failed(scheme, "a URL with no host");
            }
            boolean credentials = authority.indexOf('@') >= 0;
            String hostAndPort = credentials ? authority.substring(authority.lastIndexOf('@') + 1) : authority;
            String host;
            String portText = null;
            if (hostAndPort.startsWith("[")) {
                int close = hostAndPort.indexOf(']');
                if (close < 0) {
                    return failed(scheme, "a URL with an unterminated address literal");
                }
                host = hostAndPort.substring(0, close + 1);
                String rest = hostAndPort.substring(close + 1);
                if (rest.startsWith(":")) {
                    portText = rest.substring(1);
                } else if (!rest.isEmpty()) {
                    return failed(scheme, "a URL with a malformed authority");
                }
            } else {
                int colon = hostAndPort.lastIndexOf(':');
                if (colon >= 0) {
                    host = hostAndPort.substring(0, colon);
                    portText = hostAndPort.substring(colon + 1);
                } else {
                    host = hostAndPort;
                }
            }
            if (host.isBlank()) {
                return failed(scheme, "a URL with no host");
            }
            int port = defaultPort(scheme);
            if (portText != null && !portText.isEmpty()) {
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    return failed(scheme, "a URL with a non-numeric port");
                }
                if (port < 1 || port > 65535) {
                    return failed(scheme, "a URL naming the port %d, which is not a port".formatted(port));
                }
            }
            return new Parsed(scheme, host.toLowerCase(Locale.ROOT), port, credentials, null);
        }

        private static int defaultPort(String scheme) {
            return switch (scheme) {
                case "https" -> 443;
                case "http" -> 80;
                default -> -1;
            };
        }
    }
}
