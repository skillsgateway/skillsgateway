package io.github.jimisola.skillsgateway.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The effective-outcome rule (GW_0045, GW_0046), and the only place it lives.
 *
 * <p>A recorded run is raw evidence: it says what the connectors said, and no waiver ever rewrites
 * it. What gates an approval is the <em>effective</em> outcome, derived here from that run plus the
 * waivers active at a given instant. Deriving rather than stamping is what makes expiry free: there
 * is no stored value that can go stale, so a waiver stops suppressing on the very next evaluation
 * after it lapses, with no scheduler in the loop.
 *
 * <p>One rule covers every verdict:
 *
 * <ul>
 *   <li>A clearing verdict ({@code PASS}/{@code WARN}) stays clearing. Waivers only ever remove
 *       objections; they cannot create one.
 *   <li>A non-clearing verdict with <b>no findings</b> stays non-clearing. {@code PENDING} — and
 *       any future verdict that is a state rather than a set of findings — can never be waived
 *       away, because there is nothing to name in a waiver.
 *   <li>A non-clearing verdict <b>with</b> findings is re-derived from its residual findings by
 *       {@link Verdict#of(List)}: exactly the rule that turned severity into a state in the first
 *       place. Waive every {@code HIGH}/{@code CRITICAL} finding and the residual derives to
 *       {@code WARN} or {@code PASS}, and the verdict clears.
 * </ul>
 *
 * <p>Reusing {@code Verdict.of} rather than writing a second severity rule is deliberate: there is
 * one place where severity becomes a state, before and after waivers.
 *
 * <p>Pure and static over data, so it is exhaustively testable without a database, a repository or
 * a Spring context — the same property that makes {@link VettingChain#aggregate} trustworthy.
 */
public final class WaiverEvaluation {

    private WaiverEvaluation() {}

    /** One finding a waiver is currently suppressing, and which waiver is doing it. */
    @Schema(description = "A finding an active waiver is currently suppressing")
    public record Suppression(
            @Schema(description = "Connector whose verdict carried the finding")
            String connector,

            @Schema(description = "Finding rule identifier") String ruleId,

            @Schema(description = "Where the finding was located")
            String location,

            @Schema(description = "Waiver suppressing it") long waiverId,

            @Schema(description = "Identity that accepted the risk")
            String approvedBy,

            @Schema(description = "When the acceptance lapses")
            Instant expiresAt) {}

    /** A finding that still blocks, with the connector it came from — the reviewer's worklist. */
    @Schema(description = "A blocking finding that no active waiver covers")
    public record UncoveredFinding(
            @Schema(description = "Connector whose verdict carried the finding")
            String connector,

            @Schema(description = "Finding rule identifier") String ruleId,

            @Schema(description = "Where the finding was located")
            String location,

            @Schema(description = "How much it matters") Severity severity,

            @Schema(description = "Reviewer-facing explanation")
            String message) {}

    /**
     * The result of evaluating one run against a set of waivers.
     *
     * @param outcome the effective outcome; the one that gates approval
     * @param recordedOutcome what the run itself recorded, unchanged
     * @param suppressions every finding an active waiver is removing from the computation
     * @param uncovered the findings that still block; empty when {@code outcome} is not blocked
     * @param blockingConnectors the connectors that are the reason it blocks
     */
    public record Effect(
            VettingChain.Outcome outcome,
            VettingChain.Outcome recordedOutcome,
            List<Suppression> suppressions,
            List<UncoveredFinding> uncovered,
            List<String> blockingConnectors) {

        public Effect {
            suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
            blockingConnectors = blockingConnectors == null ? List.of() : List.copyOf(blockingConnectors);
        }

        public boolean blocked() {
            return outcome.blocked();
        }
    }

    /** The effective outcome of a snapshot with no chain run at all: blocked, and nothing to waive. */
    public static Effect noRun() {
        return new Effect(VettingChain.Outcome.BLOCKED, VettingChain.Outcome.BLOCKED, List.of(), List.of(), List.of());
    }

    /**
     * Evaluates {@code run} against {@code waivers} as of {@code now}.
     *
     * @param run the snapshot's latest recorded chain run, or null when it has none
     * @param waivers the candidate waivers — the marketplace's, unfiltered; this method decides
     *     which of them are active and which of them match
     * @param sha the commit SHA the snapshot is pinned to, matched by snapshot-scoped waivers
     * @param now the instant expiry is decided against
     */
    @Requirements({"GW_0045", "GW_0046"})
    public static Effect evaluate(VettingRepository.Run run, List<Waiver> waivers, String sha, Instant now) {
        if (run == null) {
            return noRun();
        }
        List<Waiver> candidates = waivers == null ? List.of() : waivers;
        List<Suppression> suppressions = new ArrayList<>();
        List<UncoveredFinding> uncovered = new ArrayList<>();
        List<String> blockingConnectors = new ArrayList<>();
        List<VerdictState> effectiveStates = new ArrayList<>(run.verdicts().size());

        for (VettingRepository.VerdictView verdict : run.verdicts()) {
            List<Finding> residual = new ArrayList<>(verdict.findings().size());
            for (Finding finding : verdict.findings()) {
                Waiver waiver = firstCovering(candidates, finding, sha, now);
                if (waiver == null) {
                    residual.add(finding);
                } else {
                    suppressions.add(new Suppression(
                            verdict.connector(),
                            finding.id(),
                            finding.location(),
                            waiver.id(),
                            waiver.approvedBy(),
                            waiver.expiresAt()));
                }
            }
            VerdictState effective = effectiveState(verdict, residual);
            effectiveStates.add(effective);
            if (!effective.clearing()) {
                blockingConnectors.add(verdict.connector());
                for (Finding finding : residual) {
                    if (finding.severity().atLeast(Severity.HIGH)) {
                        uncovered.add(new UncoveredFinding(
                                verdict.connector(),
                                finding.id(),
                                finding.location(),
                                finding.severity(),
                                finding.message()));
                    }
                }
            }
        }

        VettingChain.Outcome recorded = run.outcome();
        VettingChain.Outcome effective = VettingChain.aggregate(effectiveStates);
        if (!effective.blocked() && !suppressions.isEmpty()) {
            effective = VettingChain.Outcome.CLEAR_WITH_WAIVERS;
        }
        return new Effect(effective, recorded, suppressions, uncovered, blockingConnectors);
    }

    /**
     * A verdict's state once its waived findings are removed. A verdict that already clears keeps
     * its state; one that does not, and has no findings to remove, keeps its state too — which is
     * how {@code PENDING} and an empty run stay blocking no matter what waivers exist.
     */
    private static VerdictState effectiveState(VettingRepository.VerdictView verdict, List<Finding> residual) {
        if (verdict.state().clearing() || verdict.findings().isEmpty()) {
            return verdict.state();
        }
        return Verdict.of(residual).state();
    }

    private static Waiver firstCovering(List<Waiver> waivers, Finding finding, String sha, Instant now) {
        for (Waiver waiver : waivers) {
            if (waiver.covers(finding, sha, now)) {
                return waiver;
            }
        }
        return null;
    }
}
