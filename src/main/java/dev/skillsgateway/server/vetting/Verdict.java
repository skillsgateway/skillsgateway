package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a vetter answers: the normalized {@code {verdict, report-url, findings[]}} of
 * ARCHITECTURE.md §4, plus a coverage {@code summary} of what the vetter actually examined.
 *
 * @param state the conclusion
 * @param findings what led to it; may be empty, and is empty for a clean pass
 * @param reportUrl where a fuller report lives, for a vetter that produces one
 * @param summary a non-empty, reviewer-facing statement of what this vetter examined — the
 *     files it scanned and the rules it applied — recorded even for a clean pass so a pass is
 *     distinguishable in the ledger from a vetter that never ran (GW_VETTING_0023); may be {@code null}
 *     for an internally-derived verdict that examined nothing (an error, or a residual re-derivation
 *     over already-recorded findings)
 */
@Schema(description = "A vetter's answer about one snapshot")
public record Verdict(
        @Schema(description = "The conclusion") VerdictState state,
        @Schema(description = "What led to the conclusion") List<Finding> findings,

        @Schema(description = "Where a fuller external report lives, when the vetter produces one")
        String reportUrl,

        @Schema(description = "What the vetter examined: files scanned and rules applied, even for a clean pass")
        String summary) {

    public Verdict {
        if (state == null) {
            throw new IllegalArgumentException("verdict state is required");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static Verdict pass() {
        return new Verdict(VerdictState.PASS, List.of(), null, null);
    }

    /**
     * The verdict for a set of findings, derived from the worst severity present: a high or
     * critical finding fails, a low or medium one warns, and informational findings alone still
     * pass. Vetters use this rather than choosing a state themselves, so severity is the only
     * thing a new rule has to get right.
     */
    public static Verdict of(List<Finding> findings) {
        return of(findings, null);
    }

    /**
     * {@link #of(List)} carrying the vetter's coverage {@code summary} — what it examined
     * (GW_VETTING_0023). A vetter states this on every verdict it produces, including a clean pass, so
     * that "pass" is not indistinguishable from "did not run".
     */
    public static Verdict of(List<Finding> findings, String summary) {
        Severity worst = findings.stream()
                .map(Finding::severity)
                .max(Severity::compareTo)
                .orElse(null);
        if (worst == null || worst == Severity.INFO) {
            return new Verdict(VerdictState.PASS, findings, null, summary);
        }
        return new Verdict(
                worst.atLeast(Severity.HIGH) ? VerdictState.FAIL : VerdictState.WARN, findings, null, summary);
    }

    /**
     * The verdict recorded in place of a vetter an administrator switched off for this
     * marketplace (GW_VETTING_0029.2). Carries an informational finding so the disablement is visible on the
     * run rather than being a silently shorter chain; INFO severity keeps it out of the block
     * decision, and {@link VerdictState#DISABLED} is neither clearing nor blocking regardless
     * (GW_VETTING_0029.3).
     */
    @Requirements({"GW_VETTING_0029.2"})
    public static Verdict disabled(String vetter, String scope) {
        return new Verdict(
                VerdictState.DISABLED,
                List.of(new Finding(
                        "vetter-not-run",
                        Severity.INFO,
                        vetter,
                        "vetter '%s' is disabled %s and was not run".formatted(vetter, scope))),
                null,
                null);
    }

    /**
     * The verdict recorded in place of a vetter the chain never got to, because a vetter before it
     * failed and the marketplace's chain mode is {@code stop-after-fail} (GW_VETTING_0032.2).
     *
     * <p>Carries an informational finding naming the vetter that stopped the chain, so the run
     * answers "why did this one not run" without a join. {@code INFO} keeps it out of the severity
     * derivation, and {@link WaiverEvaluation} never re-derives a {@code NOT_REACHED} verdict
     * anyway: waiving this bookkeeping finding must not be able to promote a vetter that never
     * looked into positive clearing evidence.
     *
     * @param stoppedBy the vetter whose blocking verdict stopped the chain
     */
    @Requirements({"GW_VETTING_0032.2"})
    public static Verdict notReached(String vetter, String stoppedBy) {
        return new Verdict(
                VerdictState.NOT_REACHED,
                List.of(new Finding(
                        "vetter-not-reached",
                        Severity.INFO,
                        vetter,
                        "the chain stopped at '%s' and did not reach vetter '%s'".formatted(stoppedBy, vetter))),
                null,
                "not run: the chain stopped at '%s'".formatted(stoppedBy));
    }

    /** The verdict recorded for a vetter that threw or timed out; never a silent skip. */
    public static Verdict error(String vetter, String detail) {
        return new Verdict(
                VerdictState.ERROR,
                List.of(new Finding(
                        "vetter-error",
                        Severity.CRITICAL,
                        vetter,
                        "vetter '%s' produced no verdict: %s".formatted(vetter, detail))),
                null,
                null);
    }
}
