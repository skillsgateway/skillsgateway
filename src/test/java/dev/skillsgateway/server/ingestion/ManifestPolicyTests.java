package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The manifest gate over whole manifests (GW_0152), exercised without a database or a Spring
 * context so the invariant can be stated as a property of the function rather than of one wiring.
 *
 * <p>The invariant under test is the one that keeps threat T4 closed while external source support
 * arrives in increments: this gate returns {@code null} — the value {@code IngestionService} maps to
 * the held state — only when every declared source already resolves inside the served snapshot. An
 * admitted-but-unresolved source therefore still produces a violation, and it is worded differently
 * from a refusal so an operator can tell a configuration problem from a capability the gateway does
 * not have yet.
 */
class ManifestPolicyTests {

    private static final Set<String> HTTPS = Set.of("http", "https");

    private static ManifestPolicy policy(boolean enabled, int maxSources) {
        return new ManifestPolicy(new ExternalSourceAdmission(enabled, Set.of("github"), Set.of(), HTTPS, maxSources));
    }

    private static String validate(ManifestPolicy policy, String manifest) {
        return policy.validate(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static String manifest(String... pluginEntries) {
        return "{\"name\":\"m\",\"plugins\":[%s]}".formatted(String.join(",", pluginEntries));
    }

    private static String github(String name, String repo) {
        return "{\"name\":\"%s\",\"source\":{\"source\":\"github\",\"repo\":\"%s\"}}".formatted(name, repo);
    }

    @Test
    @SVCs({"SVC_GW_0152"})
    void a_local_only_manifest_passes_the_gate() {
        assertThat(validate(policy(true, 20), manifest("{\"name\":\"hello\",\"source\":\"./plugins/hello\"}")))
                .isNull();
    }

    @Test
    @SVCs({"SVC_GW_0152"})
    void an_admitted_source_is_still_a_violation_and_reads_differently_from_a_refusal() {
        String notAdmitted = validate(policy(false, 20), manifest(github("tools", "acme/tools")));
        String admitted = validate(policy(true, 20), manifest(github("tools", "acme/tools")));

        assertThat(notAdmitted).isNotNull().contains("non-local").contains("not enabled");
        // The gate never returns null for an unresolved external source: held is what null means,
        // and a held snapshot is approvable and therefore publishable.
        assertThat(admitted)
                .isNotNull()
                .contains("admits but cannot yet resolve")
                .contains("https://github.com/acme/tools");
        assertThat(admitted).isNotEqualTo(notAdmitted);
    }

    @Test
    @SVCs({"SVC_GW_0151"})
    void the_max_sources_bound_is_reached_through_a_whole_manifest() {
        String tooMany = manifest(github("a", "acme/a"), github("b", "acme/b"), github("c", "acme/c"));

        assertThat(validate(policy(true, 3), tooMany)).contains("admits but cannot yet resolve");
        // The refusal wins over the softer admitted-but-unresolvable violation recorded earlier.
        assertThat(validate(policy(true, 2), tooMany)).contains("maximum of 2");
    }

    @Test
    @SVCs({"SVC_GW_0152"})
    void a_manifest_the_gate_cannot_read_is_a_violation_whatever_the_configuration() {
        for (boolean enabled : new boolean[] {false, true}) {
            ManifestPolicy policy = policy(enabled, 20);
            assertThat(validate(policy, "not json")).isNotNull();
            assertThat(validate(policy, "[]")).isNotNull();
            assertThat(validate(policy, "{\"name\":\"m\"}")).isNotNull();
            assertThat(validate(policy, "{\"name\":\"m\",\"plugins\":[\"nope\"]}"))
                    .isNotNull();
        }
    }
}
