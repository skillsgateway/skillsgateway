package dev.skillsgateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.admin.CloneUrlNormalizer;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/** The normalizer alone, away from HTTP and persistence: the exact rule GW_INGEST_0029 states. */
class CloneUrlNormalizerTests {

    @Test
    @SVCs({"SVC_GW_INGEST_0029"})
    void host_case_a_trailing_slash_and_a_git_suffix_all_normalize_the_same() {
        String canonical = "https://github.com/acme/marketplace";
        assertThat(CloneUrlNormalizer.normalize("https://GitHub.com/acme/marketplace"))
                .isEqualTo(canonical);
        assertThat(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace/"))
                .isEqualTo(canonical);
        assertThat(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace.git"))
                .isEqualTo(canonical);
        assertThat(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace.GIT"))
                .isEqualTo(canonical);
        assertThat(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace/.git"))
                .isEqualTo(canonical);
        assertThat(CloneUrlNormalizer.normalize("https://GITHUB.COM/acme/marketplace.git/"))
                .isEqualTo(canonical);
    }

    @Test
    void the_path_keeps_its_case() {
        // Most forges treat the repository path as case-sensitive; only the host is folded.
        assertThat(CloneUrlNormalizer.normalize("https://github.com/Acme/Marketplace"))
                .isEqualTo("https://github.com/Acme/Marketplace")
                .isNotEqualTo(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace"));
    }

    @Test
    void scheme_and_port_are_part_of_the_identity() {
        assertThat(CloneUrlNormalizer.normalize("https://github.com/acme/marketplace"))
                .isNotEqualTo(CloneUrlNormalizer.normalize("http://github.com/acme/marketplace"));
        assertThat(CloneUrlNormalizer.normalize("https://example.com:8443/acme/marketplace"))
                .isEqualTo("https://example.com:8443/acme/marketplace")
                .isNotEqualTo(CloneUrlNormalizer.normalize("https://example.com/acme/marketplace"));
    }

    @Test
    void null_blank_and_unparseable_all_normalize_to_null() {
        assertThat(CloneUrlNormalizer.normalize(null)).isNull();
        assertThat(CloneUrlNormalizer.normalize("")).isNull();
        assertThat(CloneUrlNormalizer.normalize("   ")).isNull();
        assertThat(CloneUrlNormalizer.normalize("not a url")).isNull();
        // Scheme-relative and path-only values have no host to normalize against.
        assertThat(CloneUrlNormalizer.normalize("/acme/marketplace")).isNull();
    }
}
