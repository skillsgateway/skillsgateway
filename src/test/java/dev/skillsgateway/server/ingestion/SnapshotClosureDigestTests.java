package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skillsgateway.server.ingestion.SnapshotClosure.Member;
import io.github.reqstool.annotations.SVCs;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The closure digest (GW_0164): the one value that has to be a function of exactly the closure's
 * content — no more, so that member order changes nothing, and no less, so that two closures
 * differing in any field never share one.
 */
class SnapshotClosureDigestTests {

    private static final String UPSTREAM = "a".repeat(40);
    private static final String RESOLVED = "b".repeat(40);
    private static final String TREE = "c".repeat(40);

    private static Member member(String name) {
        return new Member(
                name,
                "github",
                "acme/" + name,
                null,
                null,
                "https://github.com/acme/" + name,
                RESOLVED,
                TREE,
                "_plugins/" + name,
                12,
                4096);
    }

    private static SnapshotClosure closure(Member... members) {
        return new SnapshotClosure(UPSTREAM, "1", List.of(members));
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_digest_is_a_sha256_hex_string() {
        assertThat(closure(member("tools")).digest()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_same_closure_has_the_same_digest_whatever_the_member_order() {
        assertThat(closure(member("tools"), member("extra")).digest())
                .isEqualTo(closure(member("extra"), member("tools")).digest());
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_empty_closure_has_a_digest_of_its_own() {
        String empty = closure().digest();
        assertThat(empty)
                .matches("^[0-9a-f]{64}$")
                .isNotEqualTo(closure(member("tools")).digest());
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void the_upstream_commit_and_the_transformer_version_are_inputs() {
        String base = closure(member("tools")).digest();
        assertThat(new SnapshotClosure("d".repeat(40), "1", List.of(member("tools"))).digest())
                .isNotEqualTo(base);
        assertThat(new SnapshotClosure(UPSTREAM, "2", List.of(member("tools"))).digest())
                .isNotEqualTo(base);
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void a_null_and_an_empty_declared_pin_are_different_closures() {
        Member unpinned = member("tools");
        Member emptyPin = new Member(
                unpinned.pluginName(),
                unpinned.sourceType(),
                unpinned.declaredSource(),
                "",
                unpinned.declaredSha(),
                unpinned.cloneUrl(),
                unpinned.resolvedSha(),
                unpinned.treeSha(),
                unpinned.graftPath(),
                unpinned.objectCount(),
                unpinned.inflatedBytes());
        assertThat(closure(unpinned).digest()).isNotEqualTo(closure(emptyPin).digest());
    }

    @Test
    @SVCs({"SVC_GW_0164"})
    void two_members_cannot_be_confused_with_one_by_field_boundaries() {
        // A digest built by concatenation without framing would let "ab" + "c" collide with
        // "a" + "bc"; the plugin name and the source type are adjacent fields, so this is the
        // shape that would show it.
        Member left = new Member(
                "toolsg",
                "ithub",
                "acme/tools",
                null,
                null,
                "https://github.com/acme/tools",
                RESOLVED,
                TREE,
                "_plugins/tools",
                12,
                4096);
        Member right = new Member(
                "tools",
                "github",
                "acme/tools",
                null,
                null,
                "https://github.com/acme/tools",
                RESOLVED,
                TREE,
                "_plugins/tools",
                12,
                4096);
        assertThat(closure(left).digest()).isNotEqualTo(closure(right).digest());
    }

    static Stream<Arguments> fields() {
        return Stream.of(
                Arguments.of("pluginName", (UnaryOperator<Member>) m -> new Member(
                        "other",
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("sourceType", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        "git",
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("declaredSource", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        "acme/other",
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("declaredRef", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        "v1",
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("declaredSha", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        "e".repeat(40),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("cloneUrl", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        "https://example.test/acme/tools",
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("resolvedSha", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        "e".repeat(40),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("treeSha", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        "e".repeat(40),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("graftPath", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        "_plugins/other",
                        m.objectCount(),
                        m.inflatedBytes())),
                Arguments.of("objectCount", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount() + 1,
                        m.inflatedBytes())),
                Arguments.of("inflatedBytes", (UnaryOperator<Member>) m -> new Member(
                        m.pluginName(),
                        m.sourceType(),
                        m.declaredSource(),
                        m.declaredRef(),
                        m.declaredSha(),
                        m.cloneUrl(),
                        m.resolvedSha(),
                        m.treeSha(),
                        m.graftPath(),
                        m.objectCount(),
                        m.inflatedBytes() + 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    @SVCs({"SVC_GW_0164"})
    void every_member_field_is_an_input(String field, UnaryOperator<Member> change) {
        Member original = member("tools");
        assertThat(closure(change.apply(original)).digest())
                .as("changing %s changes the digest", field)
                .isNotEqualTo(closure(original).digest());
    }
}
