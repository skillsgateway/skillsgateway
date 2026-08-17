package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Built-in connector: prompt-injection markers in a snapshot's instruction content (GW_0040).
 *
 * <p>Threat T1 is that {@code SKILL.md}, slash commands and agent definitions are <em>prose that
 * executes</em> with the agent's privileges. This connector reads that prose and looks for the
 * markers of a known payload: instruction-override phrasing, attempts to make the agent disclose
 * its system prompt, references to credential file locations, instructions to hide activity from
 * the user or the reviewer, pipe-to-shell one-liners, and invisible or bidirectional control
 * characters used to smuggle text past a human reading the diff.
 *
 * <p><b>What it cannot do — read this before trusting it.</b> These are patterns, not
 * understanding. An attacker who paraphrases ("disregard the guidance you were given earlier"),
 * splits an instruction across files, or encodes it walks straight past every rule here. A passing
 * verdict means "no known marker matched"; it is not a statement that the instructions are safe.
 * The semantic answer is the LLM review connector (ARCHITECTURE.md §14.2), which is not in this
 * chain yet. Use this as triage that tells a reviewer where to look first.
 */
@Component
public class PromptInjectionConnector implements VettingConnector {

    /** Instruction content: Markdown is where skills, commands and agents carry their prose. */
    private static final List<String> INSTRUCTION_SUFFIXES = List.of(".md", ".mdc", ".markdown", ".txt");

    private static final List<ContentRules.Rule> RULES = List.of(
            new ContentRules.Rule(
                    "instruction-override",
                    Severity.HIGH,
                    "(?i)\\b(?:ignore|disregard|forget)\\b[^\\n]{0,40}\\b(?:previous|prior|above|earlier|all)\\b"
                            + "[^\\n]{0,40}\\b(?:instruction|instructions|prompt|prompts|rule|rules|guidance)\\b",
                    "instruction-override phrasing: the text tells the agent to discard its prior instructions"),
            new ContentRules.Rule(
                    "system-prompt-disclosure",
                    Severity.HIGH,
                    "(?i)\\b(?:reveal|repeat|print|output|disclose|show|dump)\\b[^\\n]{0,40}"
                            + "\\b(?:system prompt|your instructions|initial prompt|your prompt|these instructions)\\b",
                    "the text asks the agent to disclose its system prompt or instructions"),
            new ContentRules.Rule(
                    "credential-path-reference",
                    Severity.HIGH,
                    "(?:~/\\.aws|\\.aws/credentials|~/\\.ssh|\\bid_rsa\\b|~/\\.config/gh|/etc/passwd|"
                            + "\\.npmrc\\b|\\.netrc\\b|~/\\.kube/config|\\.git-credentials)",
                    "the instructions reference a credential file location"),
            new ContentRules.Rule(
                    "concealment-instruction",
                    Severity.HIGH,
                    "(?i)\\b(?:do not|don'?t|never)\\b[^\\n]{0,30}"
                            + "\\b(?:tell|inform|mention|report|reveal|show|log)\\b[^\\n]{0,30}"
                            + "\\b(?:user|reviewer|human|operator|security|anyone)\\b",
                    "the instructions tell the agent to conceal its actions from the user or reviewer"),
            new ContentRules.Rule(
                    "pipe-to-shell",
                    Severity.HIGH,
                    "(?i)\\b(?:curl|wget)\\b[^\\n]{0,120}\\|\\s*(?:sudo\\s+)?(?:ba|z|k|)sh\\b",
                    "the instructions pipe downloaded content straight into a shell"),
            new ContentRules.Rule(
                    "exfiltration-instruction",
                    Severity.HIGH,
                    "(?i)\\b(?:send|post|upload|exfiltrate|transmit|forward)\\b[^\\n]{0,60}"
                            + "\\b(?:credential|credentials|api key|token|secret|password|env|environment variable)\\b"
                            + "[^\\n]{0,60}\\b(?:to|at)\\b\\s*(?:https?://|[\\w.-]+\\.[a-z]{2,})",
                    "the instructions tell the agent to send credentials or environment values somewhere"),
            new ContentRules.Rule(
                    "hidden-html-instruction",
                    Severity.MEDIUM,
                    "(?s)<!--(?:(?!-->).){0,400}?(?i:ignore|system prompt|do not tell|instruction)"
                            + "(?:(?!-->).){0,400}?-->",
                    "an HTML comment carries agent-directed text that a rendered view would hide"));

    /**
     * Characters that are invisible or that reorder rendered text: zero-width spaces and joiners,
     * bidirectional overrides, the byte-order mark, and the Unicode tag block used to hide ASCII
     * inside a single rendered glyph.
     */
    private static boolean invisible(int codePoint) {
        return (codePoint >= 0x200B && codePoint <= 0x200F)
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2060 && codePoint <= 0x2064)
                || (codePoint >= 0x2066 && codePoint <= 0x2069)
                || codePoint == 0xFEFF
                || (codePoint >= 0xE0000 && codePoint <= 0xE007F);
    }

    @Override
    public String name() {
        return "prompt-injection";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String description() {
        return "Pattern heuristics over the snapshot's Markdown instruction content: instruction-override"
                + " phrasing, system-prompt disclosure, credential paths, concealment, pipe-to-shell, and"
                + " invisible or bidirectional characters. A triage signal, not a semantic review — a"
                + " paraphrased payload is not detected.";
    }

    @Override
    @Requirements({"GW_0040"})
    public Verdict vet(SnapshotUnderVetting snapshot) {
        List<Finding> findings = new ArrayList<>();
        try {
            snapshot.walk((path, content) -> {
                if (!instructionContent(path)) {
                    return;
                }
                if (content == null) {
                    findings.add(new Finding(
                            "file-not-scanned",
                            Severity.INFO,
                            path,
                            "instruction file exceeds the configured scan size limit and was not scanned"));
                    return;
                }
                String text = ContentRules.text(content);
                if (text == null) {
                    findings.add(new Finding(
                            "file-not-scanned",
                            Severity.INFO,
                            path,
                            "instruction file is not valid UTF-8 and was not scanned"));
                    return;
                }
                findings.addAll(ContentRules.apply(RULES, path, text));
                findings.addAll(invisibleCharacters(path, text));
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read snapshot content", e);
        }
        return Verdict.of(findings);
    }

    private static boolean instructionContent(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return INSTRUCTION_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /** One finding per file: the point is that the file contains hidden text, not how much. */
    private static List<Finding> invisibleCharacters(String path, String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (invisible(codePoint)) {
                return List.of(new Finding(
                        "invisible-characters",
                        Severity.HIGH,
                        "%s:%d".formatted(path, ContentRules.lineOf(text, index)),
                        "instruction file contains invisible or bidirectional control characters (U+%04X),"
                                        .formatted(codePoint)
                                + " which can hide text from a human reading the diff"));
            }
            index += Character.charCount(codePoint);
        }
        return List.of();
    }
}
