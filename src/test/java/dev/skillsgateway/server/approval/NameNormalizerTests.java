package dev.skillsgateway.server.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.reqstool.annotations.SVCs;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The normalised keys (GW_APPROVAL_0019.1): lookalikes meet, near misses do not. */
class NameNormalizerTests {

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void case_separators_and_the_adrs_own_examples_reduce_to_one_key() {
        assertSameKey(
                "claude-skills",
                "Claude-Skills",
                "claude_skills",
                "claudeskills",
                "cIaude-skills",
                "claude.skills",
                "claude skills",
                "CLAUDE--SKILLS");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void confusable_letters_and_digits_reduce_to_one_key() {
        // A digit for a letter, both directions of the skeleton's capitals.
        assertSameKey("owner-tools", "0wner-tools", "OWNER-TOOLS");
        assertSameKey("hello", "he1lo", "heIlo");
        // Cyrillic а and с for Latin a and c.
        assertSameKey("code-review", "сode-review", "code-rеview");
        // The skeleton's own multi-character mapping: rn looks like m.
        assertSameKey("modern", "rnodern");
        // Dotless i.
        assertSameKey("lint", "lınt");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void compatibility_forms_and_invisible_format_characters_reduce_to_one_key() {
        // Fullwidth letters (NFKC), a ligature (NFKC), a zero-width space and joiner (Cf).
        assertSameKey("deploy", "ｄｅｐｌｏｙ", "de​ploy", "dep‍loy");
        assertSameKey("office", "oﬃce");
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void genuinely_different_names_keep_different_keys_even_one_edit_apart() {
        List<String> distinct = List.of(
                "code-review",
                "code-reviews",
                "code-rev1ew-x",
                "codereveiw",
                "deploy",
                "deplay",
                "lint",
                "list",
                "hello",
                "hallo",
                "init",
                "review",
                "claude-skills",
                "claude-skill");
        for (String a : distinct) {
            for (String b : distinct) {
                if (!a.equals(b)) {
                    assertThat(NameNormalizer.collide(a, b))
                            .as("%s vs %s", a, b)
                            .isFalse();
                }
            }
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void every_key_is_its_own_only_key() {
        for (String name : List.of("Claude-Skills", "0wner", "rnodern", "ｄeploy", "cIaude_skills", "ÄÖÜß")) {
            for (String key : NameNormalizer.keys(name)) {
                assertThat(NameNormalizer.keys(key))
                        .as("keys of %s's key %s", name, key)
                        .containsExactly(key);
            }
        }
    }

    @Test
    @SVCs({"SVC_GW_APPROVAL_0019.1"})
    void a_name_made_only_of_separators_has_an_empty_key() {
        assertThat(NameNormalizer.keys("-_. ")).containsExactly("");
    }

    private static void assertSameKey(String... names) {
        for (String name : names) {
            assertThat(NameNormalizer.collide(names[0], name))
                    .as("%s vs %s", names[0], name)
                    .isTrue();
            assertThat(NameNormalizer.collide(name, names[0]))
                    .as("%s vs %s", name, names[0])
                    .isTrue();
        }
    }
}
