package dev.skillsgateway.server.vetting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a connector answers: the normalized {@code {verdict, report-url, findings[]}} of
 * ARCHITECTURE.md §4.
 *
 * @param state the conclusion
 * @param findings what led to it; may be empty, and is empty for a clean pass
 * @param reportUrl where a fuller report lives, for a connector that produces one
 */
@Schema(description = "A vetting connector's answer about one snapshot")
public record Verdict(
        @Schema(description = "The conclusion") VerdictState state,
        @Schema(description = "What led to the conclusion") List<Finding> findings,

        @Schema(description = "Where a fuller external report lives, when the connector produces one")
        String reportUrl) {

    public Verdict {
        if (state == null) {
            throw new IllegalArgumentException("verdict state is required");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static Verdict pass() {
        return new Verdict(VerdictState.PASS, List.of(), null);
    }

    /**
     * The verdict for a set of findings, derived from the worst severity present: a high or
     * critical finding fails, a low or medium one warns, and informational findings alone still
     * pass. Connectors use this rather than choosing a state themselves, so severity is the only
     * thing a new rule has to get right.
     */
    public static Verdict of(List<Finding> findings) {
        Severity worst = findings.stream()
                .map(Finding::severity)
                .max(Severity::compareTo)
                .orElse(null);
        if (worst == null || worst == Severity.INFO) {
            return new Verdict(VerdictState.PASS, findings, null);
        }
        return new Verdict(worst.atLeast(Severity.HIGH) ? VerdictState.FAIL : VerdictState.WARN, findings, null);
    }

    /**
     * The verdict recorded in place of a connector an administrator switched off for this
     * marketplace (GW_0143). Carries an informational finding so the disablement is visible on the
     * run rather than being a silently shorter chain; INFO severity keeps it out of the block
     * decision, and {@link VerdictState#DISABLED} is neither clearing nor blocking regardless.
     */
    public static Verdict disabled(String connector, String scope) {
        return new Verdict(
                VerdictState.DISABLED,
                List.of(new Finding(
                        "connector-disabled",
                        Severity.INFO,
                        connector,
                        "connector '%s' is disabled %s and was not run".formatted(connector, scope))),
                null);
    }

    /** The verdict recorded for a connector that threw or timed out; never a silent skip. */
    public static Verdict error(String connector, String detail) {
        return new Verdict(
                VerdictState.ERROR,
                List.of(new Finding(
                        "connector-error",
                        Severity.CRITICAL,
                        connector,
                        "connector '%s' produced no verdict: %s".formatted(connector, detail))),
                null);
    }
}
