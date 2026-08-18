package dev.skillsgateway.server.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a skill's declared tools out of SKILL.md YAML frontmatter for the policy facts
 * (GW_0090). The contract is fail-closed: tools that cannot be read must never read as "no
 * tools", so every malformed shape — unterminated frontmatter, broken YAML, a non-scalar tools
 * value — raises {@link PolicyEvaluationException}, which the gate turns into a denial. Absence
 * is different from breakage: no frontmatter, or frontmatter without {@code allowed-tools},
 * honestly declares no tools.
 *
 * <p>Parsing uses SnakeYAML's {@link SafeConstructor} (plain data types only, no arbitrary object
 * construction) with default resource limits — this is hostile input from a quarantined snapshot.
 */
public final class SkillFrontmatter {

    private static final String DELIMITER = "---";
    private static final String TOOLS_KEY = "allowed-tools";

    private SkillFrontmatter() {}

    /** The declared tools of one SKILL.md, empty when none are declared. */
    public static List<String> tools(String skillMarkdown) {
        String frontmatter = frontmatterOf(skillMarkdown);
        if (frontmatter == null) {
            return List.of();
        }
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(frontmatter);
        } catch (RuntimeException e) {
            throw new PolicyEvaluationException("malformed SKILL.md frontmatter: " + e.getMessage(), e);
        }
        if (document == null) {
            return List.of();
        }
        if (!(document instanceof Map<?, ?> mapping)) {
            throw new PolicyEvaluationException("SKILL.md frontmatter is not a YAML mapping");
        }
        Object declared = mapping.get(TOOLS_KEY);
        if (declared == null) {
            return List.of();
        }
        if (declared instanceof List<?> list) {
            List<String> tools = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String || item instanceof Number || item instanceof Boolean)) {
                    throw new PolicyEvaluationException("%s entries must be scalars".formatted(TOOLS_KEY));
                }
                tools.add(item.toString().trim());
            }
            return List.copyOf(tools);
        }
        if (declared instanceof String commaSeparated) {
            return splitOutsideParentheses(commaSeparated);
        }
        throw new PolicyEvaluationException("%s must be a list or a comma-separated string".formatted(TOOLS_KEY));
    }

    /**
     * The YAML between the opening and closing {@code ---} lines, or null when the document does
     * not open with one. An opened but never closed frontmatter is malformed, not absent.
     */
    private static String frontmatterOf(String markdown) {
        char byteOrderMark = 0xFEFF;
        String text = !markdown.isEmpty() && markdown.charAt(0) == byteOrderMark ? markdown.substring(1) : markdown;
        if (!(text.startsWith(DELIMITER + "\n") || text.startsWith(DELIMITER + "\r\n"))) {
            return null;
        }
        int bodyStart = text.indexOf('\n') + 1;
        String[] lines = text.substring(bodyStart).split("\r?\n", -1);
        StringBuilder frontmatter = new StringBuilder();
        for (String line : lines) {
            if (line.strip().equals(DELIMITER)) {
                return frontmatter.toString();
            }
            frontmatter.append(line).append('\n');
        }
        throw new PolicyEvaluationException("SKILL.md frontmatter is never closed");
    }

    /**
     * Splits a comma-separated tools declaration at top level only: a comma inside parentheses —
     * {@code Bash(git add:*, git commit:*)} — belongs to the tool, not the list.
     */
    private static List<String> splitOutsideParentheses(String declaration) {
        List<String> tools = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                append(tools, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        append(tools, current);
        return List.copyOf(tools);
    }

    private static void append(List<String> tools, StringBuilder current) {
        String tool = current.toString().trim();
        if (!tool.isEmpty()) {
            tools.add(tool);
        }
    }
}
