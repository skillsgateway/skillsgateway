package dev.skillsgateway.server.mirror;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mirror URL policy (GW_FACADE_0020), as a pure function: no context, no forge, no network.
 *
 * <p>Its whole job is to be the same policy registration applies, so the cases that matter are the
 * ones where a second, laxer copy would differ — a scheme nobody allowlisted, no scheme at all, a
 * URL that does not parse — plus the one thing a push URL needs that a clone URL does not.
 */
class MirrorUrlPolicyTests {

    private static final List<String> DEFAULT_SCHEMES = List.of("http", "https");

    @Test
    @SVCs({"SVC_GW_FACADE_0020"})
    void the_mirror_url_faces_the_same_scheme_allowlist_registration_applies() {
        assertThat(MirrorUrlPolicy.refuse("https://forge.example.com/mirrors/corp.git", DEFAULT_SCHEMES))
                .isNull();
        // The allowlist is the whole check, so widening it is what admits a scheme, never the
        // mirror deciding for itself that a local path is fine.
        assertThat(MirrorUrlPolicy.refuse("file:///srv/mirror.git", DEFAULT_SCHEMES))
                .contains("scheme must be one of");
        assertThat(MirrorUrlPolicy.refuse("file:///srv/mirror.git", List.of("http", "https", "file")))
                .isNull();
        assertThat(MirrorUrlPolicy.refuse("ssh://git@forge.example.com/corp.git", DEFAULT_SCHEMES))
                .contains("scheme must be one of");
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0020"})
    void a_url_with_no_usable_scheme_is_refused_rather_than_handed_on() {
        assertThat(MirrorUrlPolicy.refuse(null, DEFAULT_SCHEMES)).contains("is required");
        assertThat(MirrorUrlPolicy.refuse("   ", DEFAULT_SCHEMES)).contains("is required");
        // A scp-style address has no scheme git would honour; this policy must refuse it rather
        // than hand it to JGit, which does understand it. Which of the two refusals it takes is not
        // the point and is not asserted.
        assertThat(MirrorUrlPolicy.refuse("git@forge.example.com:corp/mirror.git", DEFAULT_SCHEMES))
                .isNotNull();
        assertThat(MirrorUrlPolicy.refuse("https://forge.example.com/a b.git", DEFAULT_SCHEMES))
                .contains("not a valid URL");
    }

    @Test
    @SVCs({"SVC_GW_FACADE_0020"})
    void a_credential_embedded_in_the_url_is_refused_so_the_url_stays_safe_to_print() {
        assertThat(MirrorUrlPolicy.refuse("https://bot:s3cret@forge.example.com/corp.git", DEFAULT_SCHEMES))
                .contains("must not embed credentials");
        assertThat(MirrorUrlPolicy.refuse("https://token@forge.example.com/corp.git", DEFAULT_SCHEMES))
                .contains("must not embed credentials");
        // An @ in the path is not a credential, and refusing it would refuse legitimate forges.
        assertThat(MirrorUrlPolicy.refuse("https://forge.example.com/~alice@corp/mirror.git", DEFAULT_SCHEMES))
                .isNull();
    }
}
