package dev.skillsgateway.server.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;

/**
 * The typed source model (GW_0150) as the pure, total function it is — no database, no Spring
 * context, no repository.
 *
 * <p>The point being proved is that the <em>type</em> is what the parser produces, not a verdict:
 * every external form has to come back as its own variant rather than being lumped together by a
 * string-shape check, because that is what lets admission treat one type differently from another.
 * The adversarial half is that nothing falls through — an unknown type, a known type missing its
 * field, and a value that is neither a path nor an object all become a named refusal rather than an
 * exception or a silent accept.
 */
class PluginSourceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PluginSource parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return PluginSource.parse(node.isNull() ? null : node);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @SVCs({"SVC_GW_0150"})
    void a_textual_source_is_a_local_path() {
        assertThat(parse("\"./plugins/hello\"")).isEqualTo(new PluginSource.Local("./plugins/hello"));
        assertThat(((PluginSource.Local) parse("\"./plugins/hello\"")).isRepositoryRelative())
                .isTrue();
    }

    @Test
    @SVCs({"SVC_GW_0150"})
    void every_external_form_is_classified_as_its_declared_type() {
        assertThat(parse("{\"source\":\"github\",\"repo\":\"acme/tools\"}"))
                .isEqualTo(new PluginSource.GitHub("acme/tools"));
        assertThat(parse("{\"source\":\"git\",\"url\":\"https://git.example/x.git\"}"))
                .isEqualTo(new PluginSource.GitUrl("https://git.example/x.git"));
        assertThat(parse("{\"source\":\"url\",\"url\":\"https://git.example/x.git\"}"))
                .isEqualTo(new PluginSource.GitUrl("https://git.example/x.git"));
        assertThat(parse("{\"source\":\"git-subdir\",\"url\":\"https://git.example/x.git\",\"path\":\"sub\"}"))
                .isEqualTo(new PluginSource.GitSubdir("https://git.example/x.git", "sub"));
        assertThat(parse("{\"source\":\"npm\",\"package\":\"@acme/tools\"}"))
                .isEqualTo(new PluginSource.Npm("@acme/tools"));
        assertThat(parse("{\"source\":\"archive\",\"url\":\"https://example/x.tgz\"}"))
                .isEqualTo(new PluginSource.Archive("https://example/x.tgz"));
    }

    @Test
    @SVCs({"SVC_GW_0150"})
    void anything_the_parser_cannot_place_becomes_a_named_refusal() {
        // The key-per-type object shape: one canonical shape is understood and the other is refused
        // loudly, rather than the parser guessing at intent on the input class that matters most.
        assertThat(parse("{\"github\":\"acme/elsewhere\"}")).isInstanceOf(PluginSource.Unrecognised.class);
        assertThat(parse("{\"source\":\"mercurial\",\"url\":\"https://hg.example/x\"}"))
                .isEqualTo(new PluginSource.Unrecognised("source type 'mercurial'"));
        assertThat(parse("{\"source\":\"github\"}")).isInstanceOf(PluginSource.Unrecognised.class);
        assertThat(parse("{\"source\":\"git-subdir\",\"url\":\"https://git.example/x.git\"}"))
                .isInstanceOf(PluginSource.Unrecognised.class);
        assertThat(parse("42")).isInstanceOf(PluginSource.Unrecognised.class);
        assertThat(parse("[\"./plugins/hello\"]")).isInstanceOf(PluginSource.Unrecognised.class);
        assertThat(parse("null")).isInstanceOf(PluginSource.Unrecognised.class);
    }

    @Test
    @SVCs({"SVC_GW_0150"})
    void a_local_path_that_escapes_the_repository_is_not_repository_relative() {
        for (String path : new String[] {
            "", "/etc/passwd", "~/secrets", "\\\\host\\share", "../elsewhere", "a/../../b", "https://example/x"
        }) {
            assertThat(new PluginSource.Local(path).isRepositoryRelative())
                    .as("path %s", path)
                    .isFalse();
        }
    }

    @Test
    @SVCs({"SVC_GW_0150"})
    void a_github_shorthand_expands_only_when_it_is_exactly_owner_and_repo() {
        assertThat(new PluginSource.GitHub("acme/tools").cloneUrl()).isEqualTo("https://github.com/acme/tools");
        // Nothing may smuggle a second path segment, a host, or traversal into the expansion.
        for (String shorthand : new String[] {
            "acme", "acme/tools/extra", "acme/../../evil", "evil.example/acme/tools", "acme/tools?x=1", "acme tools"
        }) {
            assertThat(new PluginSource.GitHub(shorthand).cloneUrl())
                    .as("shorthand %s", shorthand)
                    .isNull();
        }
    }
}
