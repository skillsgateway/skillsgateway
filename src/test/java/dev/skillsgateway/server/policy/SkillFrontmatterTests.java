package dev.skillsgateway.server.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * SKILL.md frontmatter parsing for the skill facts (GW_0090). Tools that cannot be read must
 * never read as "no tools": every malformed shape raises, and the gate turns that into a denial.
 */
class SkillFrontmatterTests {

    @Test
    void no_frontmatter_means_no_declared_tools() {
        assertThat(SkillFrontmatter.tools("# Hello\n\nJust prose.\n")).isEmpty();
    }

    @Test
    void frontmatter_without_allowed_tools_means_no_declared_tools() {
        assertThat(SkillFrontmatter.tools("---\nname: hello\ndescription: d\n---\n# Hello\n"))
                .isEmpty();
    }

    @Test
    void tools_from_a_yaml_list() {
        String md = "---\nname: shelly\nallowed-tools:\n  - Bash(git:*)\n  - Read\n---\n# S\n";
        assertThat(SkillFrontmatter.tools(md)).containsExactly("Bash(git:*)", "Read");
    }

    @Test
    void tools_from_a_comma_separated_string_respecting_parentheses() {
        String md = "---\nallowed-tools: Bash(git add:*, git commit:*), Read, Write\n---\n";
        assertThat(SkillFrontmatter.tools(md)).containsExactly("Bash(git add:*, git commit:*)", "Read", "Write");
    }

    @Test
    void malformed_yaml_raises() {
        assertThatThrownBy(() -> SkillFrontmatter.tools("---\nallowed-tools: [unclosed\n---\n"))
                .isInstanceOf(PolicyEvaluationException.class);
    }

    @Test
    void unterminated_frontmatter_raises() {
        assertThatThrownBy(() -> SkillFrontmatter.tools("---\nname: x\n# never closed\n"))
                .isInstanceOf(PolicyEvaluationException.class);
    }

    @Test
    void non_scalar_tools_shape_raises() {
        assertThatThrownBy(() -> SkillFrontmatter.tools("---\nallowed-tools:\n  key: value\n---\n"))
                .isInstanceOf(PolicyEvaluationException.class);
    }
}
