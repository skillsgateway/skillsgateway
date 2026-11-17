package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skillsgateway.server.persistence.Marketplace;
import io.github.reqstool.annotations.SVCs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which marketplaces are internal (GW_APPROVAL_0044.2), away from any Spring context: the matcher
 * automatic approval's eligibility rests on, and the prefixes that refuse to load.
 */
class InternalSourcesTests {

    private static final String GITHUB = "https://github.com";

    private static InternalSources sources(String... prefixes) {
        return new InternalSources(List.of(prefixes), GITHUB);
    }

    private static Marketplace marketplace(String url) {
        return new Marketplace(
                1L,
                "m",
                url,
                Instant.EPOCH,
                "alice",
                null,
                null,
                null,
                null,
                Marketplace.SYNC_ON_DEMAND,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void aUrlUnderAnOrganisationPrefixIsInternal() {
        InternalSources sources = sources("https://github.com/acme");

        assertThat(sources.isInternal("https://github.com/acme/skills.git")).isTrue();
        assertThat(sources.isInternal("https://github.com/acme")).isTrue();
        assertThat(sources.isInternal("https://github.com/acme/")).isTrue();
        assertThat(sources.isInternal(marketplace("https://github.com/acme/skills.git")))
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void withNoPrefixConfiguredNothingIsInternal() {
        InternalSources sources = sources();

        assertThat(sources.isInternal(marketplace("https://github.com/acme/skills.git")))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void anUpstreamMarketplaceWithoutAUrlIsNotInternal() {
        assertThat(sources("https://github.com/acme").isInternal(marketplace(null)))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void aLookalikeOrganisationIsNotInternal() {
        InternalSources sources = sources("https://github.com/acme");

        assertThat(sources.isInternal("https://github.com/acme-evil/skills.git"))
                .isFalse();
        assertThat(sources.isInternal("https://github.com/acmeco")).isFalse();
    }

    @ParameterizedTest
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    @ValueSource(
            strings = {
                "http://github.com/acme/skills.git",
                "ssh://github.com/acme/skills.git",
                "https://github.com.evil.example/acme/skills.git",
                "https://gitlab.com/acme/skills.git",
                "https://github.com:8443/acme/skills.git"
            })
    void anotherSchemeHostOrPortIsNotInternal(String url) {
        assertThat(sources("https://github.com/acme").isInternal(url)).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void userinfoInTheUrlIsNotInternal() {
        InternalSources sources = sources("https://github.com/acme");

        assertThat(sources.isInternal("https://evil@github.com/acme/skills.git"))
                .isFalse();
        assertThat(sources.isInternal("https://github.com/acme@evil.example/skills.git"))
                .isFalse();
    }

    @ParameterizedTest
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    @ValueSource(
            strings = {
                "https://github.com/acme%2f..%2fevil/skills.git",
                "https://github.com/acme/%2e%2e/evil/skills.git",
                "https://github.com/acme/../evil/skills.git",
                "https://github.com/acme/./skills.git",
                "https://github.com/acme\\..\\evil"
            })
    void encodedSeparatorsAndDotSegmentsAreNotInternal(String url) {
        assertThat(sources("https://github.com/acme").isInternal(url)).isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void aCaseShiftedPathSegmentIsNotInternal() {
        assertThat(sources("https://github.com/acme").isInternal("https://github.com/ACME/skills.git"))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void theHostIsComparedWithoutCase() {
        assertThat(sources("https://GitHub.com/acme").isInternal("https://github.COM/acme/skills.git"))
                .isTrue();
    }

    @ParameterizedTest
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    @ValueSource(
            strings = {
                "not a url",
                "github.com/acme",
                "ftp://github.com/acme",
                "https://user@github.com/acme",
                "https://github.com/acme?tab=repositories",
                "https://github.com/acme#readme",
                "https://github.com/acme/../evil",
                "https://github.com/acme%2fevil",
                " "
            })
    void startupRefusesAnUnparseablePrefix(String prefix) {
        assertThatThrownBy(() -> sources(prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.internal-prefixes[0]");
    }

    @ParameterizedTest
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    @ValueSource(
            strings = {
                "https://github.com",
                "https://github.com/",
                "https://GITHUB.com",
                "https://github.com:443",
                "https://gitlab.com",
                "https://bitbucket.org/",
                "http://github.com"
            })
    void startupRefusesAHostWidePrefixOnAMultiTenantForge(String prefix) {
        assertThatThrownBy(() -> sources("https://github.com/acme", prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.internal-prefixes[1]")
                .hasMessageContaining("every");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void startupRefusesAHostWidePrefixOnTheGithubBaseUrlHost() {
        assertThatThrownBy(() -> new InternalSources(List.of("https://ghe.acme.example"), "https://ghe.acme.example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills-gateway.approval.auto.internal-prefixes[0]")
                .hasMessageContaining("github-base-url");
        assertThatThrownBy(() -> new InternalSources(List.of("https://GHE.acme.example/"), "https://ghe.acme.example/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void aHostWidePrefixOnASingleTenantHostLoadsAndCoversThatHost() {
        InternalSources sources = new InternalSources(List.of("https://git.acme.example"), GITHUB);

        assertThat(sources.isInternal("https://git.acme.example/team/skills.git"))
                .isTrue();
        assertThat(sources.isInternal("https://git.acme.example.evil/team/skills.git"))
                .isFalse();
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0044.2"})
    void anOrganisationPrefixOnTheGithubBaseUrlHostLoads() {
        InternalSources sources =
                new InternalSources(List.of("https://ghe.acme.example/platform"), "https://ghe.acme.example");

        assertThat(sources.isInternal("https://ghe.acme.example/platform/skills.git"))
                .isTrue();
        assertThat(sources.isInternal("https://ghe.acme.example/other/skills.git"))
                .isFalse();
    }
}
