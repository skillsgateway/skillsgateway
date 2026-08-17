package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Built-in connector: credential material committed into a snapshot (GW_0039).
 *
 * <p>Credentials in a skill repository are exfiltrated to every developer who installs it, and they
 * are the most mechanically detectable class of harmful content — shaped tokens (AWS, GitHub,
 * Slack, Google), PEM private-key blocks, and assignment-shaped high-entropy values.
 *
 * <p><b>What it cannot do.</b> It matches shapes. A credential with no distinctive shape, one split
 * across lines, one base64-wrapped, or one that simply looks like prose walks past it. A pass here
 * means "none of these patterns matched", never "there are no secrets".
 *
 * <p>Findings never echo the matched value — the ledger and the portal would then hold the secret
 * the connector was complaining about.
 */
@Component
public class SecretScanConnector implements VettingConnector {

    /** Assignment-shaped candidates: the value is entropy-tested rather than shape-tested. */
    private static final Pattern ASSIGNED_SECRET =
            Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|passwd|credential|private[_-]?key)\\b"
                    + "\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=_\\-]{24,})[\"']?");

    /** Above this, an assignment-shaped value is random enough to be a real credential. */
    private static final double ENTROPY_THRESHOLD = 4.0;

    private static final List<ContentRules.Rule> RULES = List.of(
            new ContentRules.Rule(
                    "aws-access-key-id",
                    Severity.CRITICAL,
                    "\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ABIA|ACCA)[0-9A-Z]{16}\\b",
                    "an AWS access key id is committed in this file"),
            new ContentRules.Rule(
                    "aws-secret-access-key",
                    Severity.CRITICAL,
                    "(?i)aws[^\\n]{0,24}?(?:secret|private)[^\\n]{0,24}?[\"'][A-Za-z0-9/+=]{40}[\"']",
                    "an AWS secret access key is committed in this file"),
            new ContentRules.Rule(
                    "private-key-block",
                    Severity.CRITICAL,
                    "-----BEGIN (?:RSA |DSA |EC |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY(?: BLOCK)?-----",
                    "a private key block is committed in this file"),
            new ContentRules.Rule(
                    "github-token",
                    Severity.CRITICAL,
                    "\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\\b",
                    "a GitHub access token is committed in this file"),
            new ContentRules.Rule(
                    "slack-token",
                    Severity.HIGH,
                    "\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b",
                    "a Slack token is committed in this file"),
            new ContentRules.Rule(
                    "google-api-key",
                    Severity.HIGH,
                    "\\bAIza[0-9A-Za-z_\\-]{35}\\b",
                    "a Google API key is committed in this file"),
            new ContentRules.Rule(
                    "json-web-token",
                    Severity.MEDIUM,
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b",
                    "a JSON Web Token is committed in this file"));

    @Override
    public String name() {
        return "secret-scan";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String description() {
        return "Regex and entropy rules over the snapshot's text files: cloud access keys, private key"
                + " blocks, forge and messaging tokens, and assignment-shaped high-entropy values."
                + " Shape-based, so an unshaped or obfuscated credential is not detected.";
    }

    @Override
    @Requirements({"GW_0039"})
    public Verdict vet(SnapshotUnderVetting snapshot) {
        List<Finding> findings = new ArrayList<>();
        try {
            snapshot.walk((path, content) -> {
                if (content == null) {
                    findings.add(new Finding(
                            "file-not-scanned",
                            Severity.INFO,
                            path,
                            "file exceeds the configured scan size limit and was not scanned"));
                    return;
                }
                String text = ContentRules.text(content);
                if (text == null) {
                    // Binary: reported, not silently dropped, so a reviewer knows the coverage gap.
                    findings.add(new Finding(
                            "file-not-scanned", Severity.INFO, path, "binary file; text rules do not apply"));
                    return;
                }
                findings.addAll(ContentRules.apply(RULES, path, text));
                findings.addAll(highEntropyAssignments(path, text));
            });
        } catch (java.io.IOException e) {
            // Reading the snapshot failed midway; the chain records this as an error, which blocks.
            throw new IllegalStateException("cannot read snapshot content", e);
        }
        return Verdict.of(findings);
    }

    /** Assignment-shaped values that are random enough to be credentials rather than identifiers. */
    private static List<Finding> highEntropyAssignments(String path, String text) {
        List<Finding> findings = new ArrayList<>();
        Matcher matcher = ASSIGNED_SECRET.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (ContentRules.entropy(value) >= ENTROPY_THRESHOLD) {
                findings.add(new Finding(
                        "high-entropy-assignment",
                        Severity.HIGH,
                        "%s:%d".formatted(path, ContentRules.lineOf(text, matcher.start())),
                        "a high-entropy value is assigned to '%s' in this file".formatted(matcher.group(1))));
            }
        }
        return findings;
    }
}
