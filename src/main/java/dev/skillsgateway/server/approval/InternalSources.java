package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.config.SkillsGatewayProperties;
import dev.skillsgateway.server.config.SkillsGatewayProperties.Auto;
import dev.skillsgateway.server.ingestion.UrlPrefix;
import dev.skillsgateway.server.persistence.Marketplace;
import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which marketplaces are internal, and so may be given an automatic-approval posture
 * (GW_APPROVAL_0044.2). Decided from the URL at every attempt and never stored, so a narrowed
 * prefix list takes effect at once rather than leaving a stale "trusted" bit behind.
 *
 * <p>Matching goes through {@link UrlPrefix}, the matcher upstream credentials use, so the two
 * cannot disagree about what is under a prefix.
 */
@Component
public class InternalSources {

    /** Forges where one host holds every user's namespace; a host-wide prefix there trusts strangers. */
    private static final Set<String> MULTI_TENANT_HOSTS = Set.of("github.com", "gitlab.com", "bitbucket.org");

    private final List<UrlPrefix> prefixes;

    @Autowired
    public InternalSources(SkillsGatewayProperties properties) {
        this(
                properties.approval().auto().internalPrefixes(),
                properties.ingestion().externalSources().githubBaseUrl());
    }

    InternalSources(List<String> configured, String githubBaseUrl) {
        this.prefixes = validated(configured, githubBaseUrl);
    }

    /** Hosted marketplaces are internal by construction: only a minted push scope can write them. */
    @Requirements({"GW_APPROVAL_0044.2"})
    public boolean isInternal(Marketplace marketplace) {
        return marketplace.hosted() || isInternal(marketplace.url());
    }

    @Requirements({"GW_APPROVAL_0044.2"})
    public boolean isInternal(String url) {
        UrlPrefix target = UrlPrefix.of(url);
        return target != null && prefixes.stream().anyMatch(prefix -> prefix.covers(target));
    }

    @Requirements({"GW_APPROVAL_0044.2"})
    private static List<UrlPrefix> validated(List<String> configured, String githubBaseUrl) {
        UrlPrefix base = UrlPrefix.of(githubBaseUrl);
        List<UrlPrefix> loaded = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            String text = configured.get(i);
            String name = "%s[%d] (%s)".formatted(Auto.INTERNAL_PREFIXES_KEY, i, text);
            UrlPrefix prefix = UrlPrefix.of(text);
            if (prefix == null || prefix.query() != null || prefix.fragment() != null) {
                throw new IllegalStateException(name
                        + ": must be an absolute http(s) URL with no userinfo, query, fragment, dot segments"
                        + " or encoded separators");
            }
            if (prefix.path().isEmpty()) {
                if (MULTI_TENANT_HOSTS.contains(prefix.host())) {
                    throw new IllegalStateException(name + ": names a whole multi-tenant host, which would make every"
                            + " account on it internal; give the organisation's path, such as "
                            + prefix.scheme() + "://" + prefix.host() + "/<organisation>");
                }
                if (base != null && base.host().equals(prefix.host())) {
                    throw new IllegalStateException(name + ": names the whole external-sources.github-base-url host,"
                            + " which would make every account on it internal; give the organisation's path");
                }
            }
            loaded.add(prefix);
        }
        return List.copyOf(loaded);
    }
}
