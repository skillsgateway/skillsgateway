package io.github.jimisola.skillsgateway.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/**
 * What a waiver's scope value means, and how it is matched against a finding (GW_0044).
 *
 * <p>The finding model offers exactly two stable handles: the rule id, and the path part of
 * {@link Finding#location()}. The line number is not one of them — inserting a line above a
 * finding moves it — so scope never looks at it. These two kinds are the two honest readings of
 * "this content":
 *
 * <ul>
 *   <li>{@link #SNAPSHOT} is the tightest thing expressible. It dies with the commit SHA, so the
 *       next ingestion re-blocks and the acceptance has to be made again, deliberately.
 *   <li>{@link #PATH} survives re-ingestion, which is the point of it and also its cost: it
 *       covers content that does not exist under that path yet. That is why an expiry is
 *       mandatory rather than merely recommended.
 * </ul>
 *
 * <p>There is deliberately no glob or regex syntax. A pattern language is a place where a matcher
 * bug becomes a trust-boundary bug, and prefix-on-a-segment-boundary is the whole of what a
 * reviewer means by "this skill".
 */
@Schema(description = "What a waiver's scope value means")
public enum WaiverScope {

    /** {@code scopeValue} is a commit SHA; the waiver applies only to that snapshot. */
    SNAPSHOT {
        @Override
        boolean matches(String scopeValue, String sha, String findingPath) {
            return sha != null && sha.equalsIgnoreCase(scopeValue);
        }
    },

    /** {@code scopeValue} is a repository-relative path; the waiver applies to it and below it. */
    PATH {
        @Override
        boolean matches(String scopeValue, String sha, String findingPath) {
            if (findingPath == null || findingPath.isBlank()) {
                return false;
            }
            String prefix = normalize(scopeValue);
            String path = normalize(findingPath);
            // Segment boundary, never a bare startsWith: 'plugins/a' must not cover
            // 'plugins/ab.md'.
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
    };

    /**
     * Whether a waiver with this scope and {@code scopeValue} covers a finding at
     * {@code findingPath} in a snapshot pinned to {@code sha}. The rule id and the marketplace
     * are checked by the caller; this is only the scope half.
     */
    abstract boolean matches(String scopeValue, String sha, String findingPath);

    /** Storage form: the lower-case name, matching the {@code vetting_waivers.scope_kind} check. */
    public String stored() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WaiverScope of(String stored) {
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }

    /**
     * The path part of a finding location. Locations are normally {@code path:line}; the trailing
     * {@code :line} is stripped only when it really is a line number, so a location that is not a
     * path at all (a connector name on an error verdict) survives intact and can still be named
     * by an exact-match waiver.
     */
    static String pathOf(String location) {
        if (location == null) {
            return null;
        }
        int colon = location.lastIndexOf(':');
        if (colon <= 0 || colon == location.length() - 1) {
            return location;
        }
        String suffix = location.substring(colon + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return location;
            }
        }
        return location.substring(0, colon);
    }

    /** Trims the separators that would otherwise make two spellings of one path differ. */
    private static String normalize(String path) {
        String trimmed = path.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
