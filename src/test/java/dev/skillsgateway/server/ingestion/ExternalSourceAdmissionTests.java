package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.ingestion.ExternalSourceAdmission.Decision;
import io.github.reqstool.annotations.SVCs;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Configuration-gated admission (GW_0151) as the pure function it is: the same source run through
 * every configuration, with no network, no repository and no clock in reach.
 *
 * <p>These are the adversarial cases for the gate itself. The load-bearing ones are that the
 * shipped default admits nothing, that a near-miss host never satisfies the allowlist, and that
 * {@code npm} and {@code archive} stay refused even by a configuration that names them — a bound
 * that a later resolver inherits rather than has to re-establish.
 */
class ExternalSourceAdmissionTests {

    private static final PluginSource.GitHub SOURCE = new PluginSource.GitHub("acme/tools");
    private static final Set<String> HTTPS = Set.of("http", "https");
    private static final String BASE = "https://github.com";

    private static ExternalSourceAdmission admission(
            boolean enabled, Set<String> types, Set<String> hosts, int maxSources) {
        return new ExternalSourceAdmission(enabled, types, hosts, HTTPS, maxSources, BASE);
    }

    private static ExternalSourceAdmission enabled() {
        return admission(true, Set.of("github"), Set.of(), 20);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void the_shipped_default_admits_nothing() {
        Decision decision = admission(false, Set.of("github"), Set.of(), 20).decide(SOURCE, "tools", 0);

        assertThat(decision).isInstanceOf(Decision.Refused.class);
        // GW_0003's phrase survives: an unconfigured gateway records exactly what it always did.
        assertThat(((Decision.Refused) decision).violation())
                .contains("non-local")
                .contains("not enabled");
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void an_enabled_gateway_admits_a_github_source_within_its_bounds() {
        assertThat(enabled().decide(SOURCE, "tools", 0))
                .isEqualTo(new Decision.Admitted(SOURCE, "https://github.com/acme/tools"));
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void a_local_path_needs_no_configuration_and_an_escaping_one_is_refused() {
        assertThat(admission(false, Set.of(), Set.of(), 20)
                        .decide(new PluginSource.Local("./plugins/hello"), "hello", 0))
                .isInstanceOf(Decision.Local.class);
        assertThat(enabled().decide(new PluginSource.Local("../elsewhere"), "hello", 0))
                .isInstanceOf(Decision.Refused.class);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void a_type_outside_the_allowlist_is_refused() {
        Decision decision = admission(true, Set.of("git"), Set.of(), 20).decide(SOURCE, "tools", 0);

        assertThat(decision).isInstanceOf(Decision.Refused.class);
        assertThat(((Decision.Refused) decision).violation())
                .contains("'github'")
                .contains("allowlist");
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void the_host_allowlist_is_exact_and_a_near_miss_does_not_satisfy_it() {
        assertThat(admission(true, Set.of("github"), Set.of("github.com"), 20).decide(SOURCE, "tools", 0))
                .isInstanceOf(Decision.Admitted.class);
        // github.com must not be satisfied by a host that merely contains or ends with it.
        assertThat(admission(true, Set.of("git"), Set.of("github.com"), 20)
                        .decide(new PluginSource.GitUrl("https://evil-github.com/acme/tools"), "tools", 0))
                .isInstanceOf(Decision.Refused.class);
        assertThat(admission(true, Set.of("git"), Set.of("github.com"), 20)
                        .decide(new PluginSource.GitUrl("https://github.com.evil.example/acme/tools"), "tools", 0))
                .isInstanceOf(Decision.Refused.class);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void a_scheme_outside_the_gateways_url_allowlist_is_refused() {
        ExternalSourceAdmission strict = new ExternalSourceAdmission(true, Set.of("git"), Set.of(), HTTPS, 20, BASE);

        for (String url :
                new String[] {"file:///etc/passwd", "ssh://git@evil.example/repo.git", "git://evil.example/repo.git"}) {
            Decision decision = strict.decide(new PluginSource.GitUrl(url), "tools", 0);
            assertThat(decision).as("url %s", url).isInstanceOf(Decision.Refused.class);
            assertThat(((Decision.Refused) decision).violation()).contains("scheme");
        }
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void the_source_beyond_the_configured_maximum_is_refused() {
        ExternalSourceAdmission bounded = admission(true, Set.of("github"), Set.of(), 2);

        assertThat(bounded.decide(SOURCE, "tools", 0)).isInstanceOf(Decision.Admitted.class);
        assertThat(bounded.decide(SOURCE, "tools", 1)).isInstanceOf(Decision.Admitted.class);

        Decision third = bounded.decide(SOURCE, "tools", 2);
        assertThat(third).isInstanceOf(Decision.Refused.class);
        assertThat(((Decision.Refused) third).violation()).contains("maximum of 2");
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void npm_and_archive_are_refused_by_every_configuration_including_one_that_names_them() {
        ExternalSourceAdmission permissive = new ExternalSourceAdmission(
                true, Set.of("github", "git", "git-subdir", "npm", "archive"), Set.of(), HTTPS, 100, BASE);

        assertThat(permissive.decide(new PluginSource.Npm("@acme/tools"), "tools", 0))
                .isInstanceOf(Decision.Refused.class);
        assertThat(permissive.decide(new PluginSource.Archive("https://example/x.tgz"), "tools", 0))
                .isInstanceOf(Decision.Refused.class);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void an_unrecognised_source_is_refused_however_permissive_the_configuration() {
        ExternalSourceAdmission permissive =
                new ExternalSourceAdmission(true, Set.of("github", "git", "git-subdir"), Set.of(), HTTPS, 100, BASE);

        assertThat(permissive.decide(new PluginSource.Unrecognised("source type 'mercurial'"), "tools", 0))
                .isInstanceOf(Decision.Refused.class);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void a_github_shorthand_that_does_not_expand_is_refused_rather_than_guessed_at() {
        Decision decision = enabled().decide(new PluginSource.GitHub("acme/../../evil"), "tools", 0);

        assertThat(decision).isInstanceOf(Decision.Refused.class);
    }
}
