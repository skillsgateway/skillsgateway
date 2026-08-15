package io.github.jimisola.skillsgateway.vetting;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for the pattern-matching built-in connectors: strict UTF-8 decoding (which
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
     * binary test these connectors use. A binary blob is not silently ignored: callers report it,
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

    /** Every match of every rule in one file, located at {@code path:line}, deduplicated per line. */
    static List<Finding> apply(List<Rule> rules, String path, String text) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            int lastLine = -1;
            while (matcher.find()) {
                int line = lineOf(text, matcher.start());
                if (line == lastLine) {
                    continue;
                }
                lastLine = line;
                findings.add(new Finding(rule.id(), rule.severity(), "%s:%d".formatted(path, line), rule.message()));
            }
        }
        return findings;
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
