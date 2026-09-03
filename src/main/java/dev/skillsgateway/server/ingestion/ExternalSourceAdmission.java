package dev.skillsgateway.server.ingestion;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import io.github.reqstool.annotations.Requirements;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a parsed {@link PluginSource} may be admitted, decided from configuration alone
 * (GW_0151).
 *
 * <p>Pure by construction: no network call, no repository access, no clock. A refused manifest must
 * not be able to cause traffic merely by being refused, and admission that is a function of its
 * arguments can be run through every configuration in one test class.
 *
 * <p>The order of the checks is load-bearing. {@code npm} and {@code archive} are refused
 * <em>above</em> the enabled branch, so no configuration can reach them.
 */
public record ExternalSourceAdmission(
        boolean enabled,
        Set<String> allowedTypes,
        Set<String> allowedHosts,
        Set<String> allowedUrlSchemes,
        int maxSources,
        /* Where an owner/repo shorthand resolves; see ExternalSources.githubBaseUrl. */
        String shorthandBaseUrl) {

    /** The outcome for one plugin entry. */
    public sealed interface Decision {

        /** A path inside the marketplace repository; nothing to resolve. */
        record Local() implements Decision {}

        /**
         * An external source this gateway is configured to accept. It is not yet servable — see
         * {@link ManifestPolicy}, which turns it into a violation under GW_0152 until a resolver
         * exists.
         */
        record Admitted(PluginSource source, String cloneUrl) implements Decision {}

        /** Refused, with the message recorded as the snapshot's violation. */
        record Refused(String violation) implements Decision {}
    }

    public ExternalSourceAdmission {
        allowedTypes = allowedTypes == null ? Set.of() : Set.copyOf(allowedTypes);
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        allowedUrlSchemes = allowedUrlSchemes == null ? Set.of() : Set.copyOf(allowedUrlSchemes);
    }

    /** The admission a running gateway's configuration describes. */
    public static ExternalSourceAdmission from(SkillsGatewayProperties properties) {
        SkillsGatewayProperties.ExternalSources external =
                properties.ingestion().externalSources();
        return new ExternalSourceAdmission(
                external.enabled(),
                lowercased(external.allowedTypes()),
                lowercased(external.allowedHosts()),
                lowercased(properties.allowedUrlSchemes()),
                external.maxSources(),
                external.githubBaseUrl());
    }

    private static Set<String> lowercased(List<String> values) {
        return values == null
                ? Set.of()
                : values.stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(v -> v.trim().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * @param pluginName the plugin the source belongs to, for the violation message
     * @param externalSoFar how many external sources this manifest has already declared, so the
     *     {@code max-sources} bound is on the external sources rather than on the plugin count
     */
    @Requirements({"GW_0003", "GW_0151"})
    public Decision decide(PluginSource source, String pluginName, int externalSoFar) {
        return switch (source) {
            case PluginSource.Local local ->
                local.isRepositoryRelative()
                        ? new Decision.Local()
                        : refuse(pluginName, "only relative paths inside the marketplace repository are allowed");
            // Above the enabled branch on purpose: a package manager's content belongs to the
            // repository manager the gateway complements, and an archive has no commit identity to
            // pin, so no setting may reach either.
            case PluginSource.Npm ignored -> refuse(pluginName, "npm sources are not supported");
            case PluginSource.Archive ignored -> refuse(pluginName, "archive sources are not supported");
            case PluginSource.Unrecognised unrecognised ->
                refuse(pluginName, "the gateway does not understand " + unrecognised.detail());
            // Understood and refused anyway, so it does not read as unrecognised: the two are
            // different problems and only one of them is the operator's typo.
            case PluginSource.Unsupported unsupported -> refuse(pluginName, unsupported.detail());
            case PluginSource.GitHub shorthand ->
                external(shorthand, shorthand.cloneUrl(shorthandBaseUrl), pluginName, externalSoFar);
            case PluginSource.GitUrl git -> external(git, git.url(), pluginName, externalSoFar);
            case PluginSource.GitSubdir subdir -> external(subdir, subdir.url(), pluginName, externalSoFar);
        };
    }

    private Decision external(PluginSource source, String cloneUrl, String pluginName, int externalSoFar) {
        if (!enabled) {
            return refuse(pluginName, "external plugin sources are not enabled on this gateway");
        }
        if (!allowedTypes.contains(source.typeName())) {
            return refuse(
                    pluginName, "source type '%s' is not in the configured allowlist".formatted(source.typeName()));
        }
        if (externalSoFar >= maxSources) {
            return refuse(
                    pluginName,
                    "the manifest declares more than the configured maximum of %d external sources"
                            .formatted(maxSources));
        }
        if (cloneUrl == null) {
            return refuse(
                    pluginName, "a %s source the gateway cannot turn into a clone URL".formatted(source.typeName()));
        }
        URI uri;
        try {
            uri = new URI(cloneUrl);
        } catch (URISyntaxException e) {
            return refuse(pluginName, "a source URL that is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedUrlSchemes.contains(scheme)) {
            return refuse(pluginName, "URL scheme '%s' is not allowed".formatted(scheme));
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        // Exact-host matching, never a suffix or pattern match: an allowlist entry of github.com
        // must not be satisfied by evil-github.com.
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
            return refuse(pluginName, "host '%s' is not in the configured allowlist".formatted(host));
        }
        return new Decision.Admitted(source, cloneUrl);
    }

    /**
     * Every refusal says "non-local source", whatever the specific reason: it is the phrase
     * GW_0003's behaviour has always been recorded under, and the detail follows it.
     */
    private static Decision refuse(String pluginName, String reason) {
        return new Decision.Refused("plugin '%s' has a non-local source: %s".formatted(pluginName, reason));
    }
}
