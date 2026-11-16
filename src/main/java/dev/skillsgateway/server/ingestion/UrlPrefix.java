package dev.skillsgateway.server.ingestion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * An http(s) URL in the canonical form a prefix may be matched against, or {@code null}. A URL
 * with userinfo, dot segments, encoded dots, slashes or backslashes has none: those are the
 * spellings that read as inside a prefix to a comparison and outside it to a server.
 *
 * <p>Shared by everything that decides by URL prefix, so that a credential and an internal source
 * normalise a URL identically and cannot disagree about what is under a prefix.
 */
public record UrlPrefix(String scheme, String host, int port, String path, String query, String fragment) {

    public static UrlPrefix of(String text) {
        if (text == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return null;
        }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        String lowered = rawPath.toLowerCase(Locale.ROOT);
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || rawPath.contains("\\")
                || lowered.contains("%2e")
                || lowered.contains("%2f")
                || lowered.contains("%5c")
                || !uri.normalize().getRawPath().equals(rawPath)) {
            return null;
        }
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        String path = rawPath;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new UrlPrefix(
                scheme, uri.getHost().toLowerCase(Locale.ROOT), port, path, uri.getRawQuery(), uri.getRawFragment());
    }

    /** Same origin, and the path equal to this one's or below it by whole segments. */
    public boolean covers(UrlPrefix other) {
        return scheme.equals(other.scheme)
                && host.equals(other.host)
                && port == other.port
                && (other.path.equals(path) || other.path.startsWith(path + "/"));
    }

    /** Equal as prefixes: the query and fragment are refused at load, so they never differ. */
    @Override
    public boolean equals(Object o) {
        return o instanceof UrlPrefix t
                && scheme.equals(t.scheme)
                && host.equals(t.host)
                && port == t.port
                && path.equals(t.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, host, port, path);
    }
}
