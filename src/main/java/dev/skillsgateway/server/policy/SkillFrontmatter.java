package dev.skillsgateway.server.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a skill's SKILL.md YAML frontmatter. Two readers share it: the policy facts (GW_0090),
 * which need the declared tools and must fail closed, and the conformance connector (GW_0167),
 * which needs the whole mapping and must never throw.
 *
 * <p>{@link #tools(String)} keeps the fail-closed contract: tools that cannot be read must never
 * read as "no tools", so every malformed shape — unterminated frontmatter, broken YAML, a
 * non-scalar tools value — raises {@link PolicyEvaluationException}, which the gate turns into a
 * denial. Absence is different from breakage: no frontmatter, or frontmatter without
 * {@code allowed-tools}, honestly declares no tools. {@link #parse(String)} is the same work
 * without the throwing: it reports breakage as a {@link Parsed#error()} for a caller whose job is
 * to describe the defect rather than refuse the content.
 *
 * <p>Parsing uses SnakeYAML's {@link SafeConstructor} (plain data types only, no arbitrary object
 * construction) with the resource limits below stated explicitly — this is hostile input from a
 * quarantined snapshot, and a limit that is inherited can be relaxed by a dependency upgrade
 * nobody reads.
 */
public final class SkillFrontmatter {

    private static final String DELIMITER = "---";
    private static final String TOOLS_KEY = "allowed-tools";

    /** Refuses an alias bomb ("billion laughs"): SnakeYAML's own default, restated so it stays. */
    private static final int MAX_ALIASES = 50;

    /** Refuses a nesting bomb. Likewise SnakeYAML's default, restated. */
    private static final int MAX_NESTING_DEPTH = 50;

    /**
     * Bounds the document before it is parsed. Tighter than SnakeYAML's 3 MiB default and
     * unreachable on either caller's path: the policy facts refuse a SKILL.md over 256 KiB before
     * parsing it, and the vetting walk hands over nothing above {@code max-file-bytes} (1 MiB).
     */
    private static final int MAX_CODE_POINTS = 1024 * 1024;

    private SkillFrontmatter() {}

    /**
     * One SKILL.md's frontmatter: its fields, or why they could not be read.
     *
     * @param fields the frontmatter mapping, or {@code null} when the document carries no
     *     frontmatter block at all or when reading it failed
     * @param error why the block could not be read, or {@code null} when there was nothing wrong
     */
    public record Parsed(Map<String, Object> fields, String error) {

        /** No frontmatter block at all — an honest absence, not a defect. */
        public boolean absent() {
            return fields == null && error == null;
        }

        /** The block exists but could not be read as a YAML mapping. */
        public boolean malformed() {
            return error != null;
        }
    }

    /**
     * The frontmatter of one SKILL.md. Never throws: an unreadable block comes back as
     * {@link Parsed#error()} so a caller can report it rather than refuse the whole snapshot.
     */
    public static Parsed parse(String skillMarkdown) {
        String frontmatter;
        try {
            frontmatter = frontmatterOf(skillMarkdown);
        } catch (PolicyEvaluationException e) {
            return new Parsed(null, e.getMessage());
        }
        if (frontmatter == null) {
            return new Parsed(null, null);
        }
        Object document;
        try {
            document = new Yaml(new SafeConstructor(loaderOptions())).load(frontmatter);
        } catch (RuntimeException e) {
            return new Parsed(null, "malformed SKILL.md frontmatter: " + e.getMessage());
        }
        if (document == null) {
            return new Parsed(Map.of(), null);
        }
        if (!(document instanceof Map<?, ?> mapping)) {
            return new Parsed(null, "SKILL.md frontmatter is not a YAML mapping");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new Parsed(Collections.unmodifiableMap(fields), null);
    }

    /** The declared tools of one SKILL.md, empty when none are declared. */
    public static List<String> tools(String skillMarkdown) {
        Parsed parsed = parse(skillMarkdown);
        if (parsed.malformed()) {
            throw new PolicyEvaluationException(parsed.error());
        }
        if (parsed.absent()) {
            return List.of();
        }
        Object declared = parsed.fields().get(TOOLS_KEY);
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

    private static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setCodePointLimit(MAX_CODE_POINTS);
        return options;
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
