package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for the pattern-matching built-in vetters: UTF-8 decoding with the binary
 * filter, line-accurate locations, and a rule type.
 */
final class ContentRules {

    private ContentRules() {}

    /**
     * One pattern rule: a stable id, what it means, and how much it matters. A {@code mentionable}
     * rule's match that reads as a mention is reported at {@link Severity#MEDIUM} (GW_VETTING_0069).
     */
    record Rule(String id, Severity severity, Pattern pattern, String message, boolean mentionable) {

        Rule(String id, Severity severity, String regex, String message) {
            this(id, severity, Pattern.compile(regex), message, false);
        }

        /** This rule, downgraded where a match reads as a mention rather than an instruction. */
        Rule whenMentionedWarn() {
            return new Rule(id, severity, pattern, message, true);
        }
    }

    /** How far back the clause before a match is read for a mention marker. */
    private static final int CLAUSE_WINDOW = 240;

    private static final Pattern PROHIBITION =
            Pattern.compile("(?i)(?<![-\\w])(?:never|do not|don'?t|must not|should not|avoid|refuse)(?![-\\w])");

    private static final Pattern EXAMPLE =
            Pattern.compile("(?i)(?<![-\\w])(?:like|such as|e\\.g\\.|for example|for instance)(?![-\\w])");

    private static final String MENTION_NOTE =
            " (quoted or prohibited in its own clause, so it reads as a mention: check it is a warning, not an"
                    + " instruction)";

    /**
     * Decodes a blob as UTF-8, returning {@code null} only when it is binary, meaning it holds a NUL
     * byte (GW_VETTING_0043). Invalid sequences decode to replacement characters instead: a shell, Node
     * and an agent's Markdown loader all still read such a file, so it must be scanned. A binary blob
     * is not silently ignored: callers report it, because "the scanner could not read this file" is
     * information a reviewer needs.
     */
    @Requirements({"GW_VETTING_0043"})
    static String text(byte[] content) {
        return isBinary(content) ? null : new String(content, StandardCharsets.UTF_8);
    }

    /** Whether {@code content} is binary for scanning purposes: absent, or holding a NUL byte. */
    private static boolean isBinary(byte[] content) {
        if (content == null) {
            return true;
        }
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every match of every rule in one file, located at {@code path:line}, at most one finding per
     * rule id and line — several patterns may share an id, and one line is one thing to judge.
     */
    @Requirements({"GW_VETTING_0069"})
    static List<Finding> apply(List<Rule> rules, String path, String text) {
        Map<String, Finding> findings = new LinkedHashMap<>();
        LineIndex lines = LineIndex.of(text);
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(InterruptibleText.of(text));
            while (matcher.find()) {
                int line = lines.lineOf(matcher.start());
                boolean mention = rule.mentionable() && mention(text, matcher.start());
                Finding finding = new Finding(
                        rule.id(),
                        mention ? Severity.MEDIUM : rule.severity(),
                        "%s:%d".formatted(path, line),
                        mention ? rule.message() + MENTION_NOTE : rule.message());
                // One finding per rule and line; a plain match outranks a mention on the same line.
                findings.merge(
                        rule.id() + ":" + line,
                        finding,
                        (kept, next) -> next.severity().atLeast(kept.severity()) ? next : kept);
            }
        }
        return new ArrayList<>(findings.values());
    }

    /**
     * Whether the match at {@code start} reads as a mention (GW_VETTING_0069): its clause holds a
     * prohibition before it, or it sits inside double quotes in a clause that introduces an example.
     * Backticks are not a marker, because a real instruction puts its command in backticks.
     */
    static boolean mention(String text, int start) {
        String clause = clauseBefore(text, start);
        if (PROHIBITION.matcher(clause).find()) {
            return true;
        }
        boolean quoted = clause.chars().filter(c -> c == '"').count() % 2 == 1
                || clause.lastIndexOf('\u201c') > clause.lastIndexOf('\u201d');
        return quoted && EXAMPLE.matcher(clause).find();
    }

    /**
     * The text before {@code start} back to the previous sentence or clause break or blank line, at
     * most {@value #CLAUSE_WINDOW} characters. Single newlines are crossed, because prose is wrapped;
     * the period of "e.g." and "i.e." is not a break.
     */
    private static String clauseBefore(String text, int start) {
        int floor = Math.max(0, start - CLAUSE_WINDOW);
        for (int i = start - 1; i > floor; i--) {
            char c = text.charAt(i - 1);
            char next = text.charAt(i);
            if (c == '\n' && next == '\n') {
                return text.substring(i, start);
            }
            if (".!?;:".indexOf(c) >= 0 && Character.isWhitespace(next) && !abbreviation(text, i - 1)) {
                return text.substring(i, start);
            }
        }
        return text.substring(floor, start);
    }

    private static boolean abbreviation(String text, int period) {
        return period >= 3
                && text.charAt(period) == '.'
                && text.charAt(period - 2) == '.'
                && ("eg".equalsIgnoreCase("" + text.charAt(period - 3) + text.charAt(period - 1))
                        || "ie".equalsIgnoreCase("" + text.charAt(period - 3) + text.charAt(period - 1)));
    }

    /** How many distinct rule ids a rule list carries. */
    static long ruleCount(List<Rule> rules) {
        return rules.stream().map(Rule::id).distinct().count();
    }

    /** How many unscanned paths an aggregated entry names before counting the rest. */
    static final int NAMED_UNSCANNED = 20;

    /**
     * One informational entry for a set of files a vetter could not read, rather than one per file
     * (GW_VETTING_0043): a vendored tree of large assets would otherwise bury the findings that matter
     * under rows that all say the same thing. It names the first {@value #NAMED_UNSCANNED} paths and
     * counts the rest, and there is none at all when the set is empty.
     *
     * @param reason what the files have in common, completing "N file(s) ..."
     */
    @Requirements({"GW_VETTING_0043"})
    static List<Finding> notScanned(List<String> paths, String reason) {
        if (paths.isEmpty()) {
            return List.of();
        }
        String named = String.join(", ", paths.subList(0, Math.min(NAMED_UNSCANNED, paths.size())));
        int more = paths.size() - NAMED_UNSCANNED;
        return List.of(new Finding(
                "file-not-scanned",
                Severity.INFO,
                null,
                "%d file(s) %s: %s%s"
                        .formatted(paths.size(), reason, named, more > 0 ? " (+%d more)".formatted(more) : "")));
    }

    /** The coverage-summary clause for skipped files, or nothing when none was skipped (GW_VETTING_0043). */
    static String skipped(int oversize, int unreadable, String unreadableKind) {
        if (oversize == 0 && unreadable == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (oversize > 0) {
            parts.add("%d over the size limit".formatted(oversize));
        }
        if (unreadable > 0) {
            parts.add("%d %s".formatted(unreadable, unreadableKind));
        }
        return "; %d file(s) not scanned (%s)".formatted(oversize + unreadable, String.join(", ", parts));
    }

    static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Shannon entropy over the characters of a candidate token, in bits per character. Random-looking
     * secrets sit above 4; English words and identifiers sit well below.
     */
    static double entropy(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        int[] counts = new int[128];
        int considered = 0;
        for (char c : value.toCharArray()) {
            if (c < 128) {
                counts[c]++;
                considered++;
            }
        }
        double entropy = 0;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / considered;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }
}
