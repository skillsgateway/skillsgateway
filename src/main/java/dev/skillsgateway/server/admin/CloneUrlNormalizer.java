package dev.skillsgateway.server.admin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Reduces a clone URL to the shape two registrations would collide on: lowercased scheme and
 * host, no trailing slash, and the {@code .git} suffix dropped so {@code …/m} and {@code
 * …/m.git} compare equal. The path itself keeps its case — most forges treat a repository path as
 * case-sensitive, so lowercasing it would fold together URLs that are not actually the same
 * upstream.
 *
 * <p>Mirrors the portal's client-side aid ({@code normalizeCloneUrl} in {@code form-rules.ts})
 * exactly, so a caller that bypasses the portal — the estate reconciler, a direct API client —
 * gets the same warning a human registering through the UI would have seen first.
 */
public final class CloneUrlNormalizer {

    private CloneUrlNormalizer() {}

    /** Null when {@code url} is null, blank, or not parseable as an absolute URL with a host. */
    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // Order matters: ".../repo.git/" has the slash after the suffix, ".../repo/.git" before
        // it, so a trailing slash is stripped both before and after the ".git" removal.
        path = path.replaceFirst("/+$", "").replaceFirst("(?i)\\.git$", "").replaceFirst("/+$", "");
        String authority = host.toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority + path;
    }
}
