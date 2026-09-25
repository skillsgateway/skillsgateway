package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for the pattern-matching built-in vetters: strict UTF-8 decoding (which
 * doubles as the binary filter), line-accurate locations, and a rule type.
 */
final class ContentRules {

    private ContentRules() {}

    /** One pattern rule: a stable id, what it means, and how much it matters. */
    record Rule(String id, Severity severity, Pattern pattern, String message) {

        Rule(String id, Severity severity, String regex, String message) {
            this(id, severity, Pattern.compile(regex), message);
        }
    }

    /**
     * Decodes a blob as UTF-8, returning {@code null} when it is not valid UTF-8 — which is the
     * binary test these vetters use. A binary blob is not silently ignored: callers report it,
     * because "the scanner could not read this file" is information a reviewer needs.
     */
    static String text(byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Every match of every rule in one file, located at {@code path:line}, at most one finding per
     * rule id and line — several patterns may share an id, and one line is one thing to judge.
     */
    static List<Finding> apply(List<Rule> rules, String path, String text) {
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                int line = lineOf(text, matcher.start());
                if (seen.add(rule.id() + ":" + line)) {
                    findings.add(
                            new Finding(rule.id(), rule.severity(), "%s:%d".formatted(path, line), rule.message()));
                }
            }
        }
        return findings;
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
