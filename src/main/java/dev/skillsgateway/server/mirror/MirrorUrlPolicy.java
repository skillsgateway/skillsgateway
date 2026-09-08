package dev.skillsgateway.server.mirror;

import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * What the gateway will accept as a mirror URL (GW_FACADE_0020).
 *
 * <p>The scheme allowlist is the one {@code MarketplaceRegistrationService} applies to a
 * registration, read from the same {@code skills-gateway.allowed-url-schemes} property, for the
 * same reason: both are addresses an operator supplies and the gateway then dereferences, and two
 * policies would be one policy plus a gap. Fail-closed in the same way too — a URL with no scheme,
 * or one that will not parse, is refused rather than handed to JGit to interpret.
 *
 * <p>Userinfo is refused on top of that, which registration has no need to do. A push URL is the
 * one place a standing write credential would naturally be embedded, and a credential inside a URL
 * is a credential inside every log line, exception message and drift report that prints one. The
 * refusal is what makes the URL safe to print everywhere else in this package.
 */
public final class MirrorUrlPolicy {

    private MirrorUrlPolicy() {}

    /** Refusal reason, or null when the URL is acceptable. Pure: nothing here contacts anything. */
    @Requirements({"GW_FACADE_0020"})
    public static String refuse(String url, List<String> allowedSchemes) {
        if (url == null || url.isBlank()) {
            return "skills-gateway.mirror.url is required when the mirror is enabled";
        }
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            return "skills-gateway.mirror.url is not a valid URL";
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
            return "skills-gateway.mirror.url scheme must be one of %s".formatted(allowedSchemes);
        }
        if (parsed.getRawUserInfo() != null || authorityOf(url).indexOf('@') >= 0) {
            return "skills-gateway.mirror.url must not embed credentials; use"
                    + " skills-gateway.mirror.username and skills-gateway.mirror.token";
        }
        return null;
    }

    /**
     * The authority component as written, so an {@code @} in a path is not read as embedded
     * userinfo. {@code URI} already answers this for a URL it parses cleanly; the string form is
     * what catches the ones it parses into an opaque URI instead.
     */
    private static String authorityOf(String url) {
        int authority = url.indexOf("://");
        if (authority < 0) {
            return "";
        }
        int slash = url.indexOf('/', authority + 3);
        return url.substring(authority + 3, slash < 0 ? url.length() : slash);
    }
}
