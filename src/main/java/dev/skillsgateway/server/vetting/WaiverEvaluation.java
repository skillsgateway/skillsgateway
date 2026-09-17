package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The effective-outcome rule (GW_VETTING_0008, GW_VETTING_0009), and the only place it lives.
 *
 * <p>A recorded run is raw evidence: it says what the vetters said, and no waiver ever rewrites
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
 * <p>One rule sits above all of that: a run carrying any {@link VerdictState#NOT_REACHED} verdict is
 * {@link VettingChain.Outcome#BLOCKED}, whatever the waivers say (GW_VETTING_0032.3). A chain that
 * stopped early left vetters that never looked at the content, so waiving the finding that stopped
 * it makes the next run get further — it does not make this one complete.
 *
 * <p>Pure and static over data, so it is exhaustively testable without a database, a repository or
 * a Spring context — the same property that makes {@link VettingChain#aggregate} trustworthy.
 */
public final class WaiverEvaluation {

    private WaiverEvaluation() {}

    /** One finding a waiver is currently suppressing, and which waiver is doing it. */
    @Schema(description = "A finding an active waiver is currently suppressing")
    public record Suppression(
            @Schema(description = "Vetter whose verdict carried the finding")
            String vetter,

            @Schema(description = "Finding rule identifier") String ruleId,

            @Schema(description = "Where the finding was located")
            String location,

            @Schema(description = "Waiver suppressing it") long waiverId,

            @Schema(description = "Identity that accepted the risk")
            String approvedBy,

            @Schema(description = "When the acceptance lapses")
            Instant expiresAt) {}

    /** A finding that still blocks, with the vetter it came from — the reviewer's worklist. */
    @Schema(description = "A blocking finding that no active waiver covers")
    public record UncoveredFinding(
            @Schema(description = "Vetter whose verdict carried the finding")
            String vetter,

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
     * @param blockingVetters the vetters that are the reason it blocks
     */
    public record Effect(
            VettingChain.Outcome outcome,
            VettingChain.Outcome recordedOutcome,
            List<Suppression> suppressions,
            List<UncoveredFinding> uncovered,
            List<String> blockingVetters) {

        public Effect {
            suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
            blockingVetters = blockingVetters == null ? List.of() : List.copyOf(blockingVetters);
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
    @Requirements({"GW_VETTING_0008", "GW_VETTING_0009", "GW_VETTING_0032.3"})
    public static Effect evaluate(VettingRepository.Run run, List<Waiver> waivers, String sha, Instant now) {
        if (run == null) {
            return noRun();
        }
        List<Waiver> candidates = waivers == null ? List.of() : waivers;
        List<Suppression> suppressions = new ArrayList<>();
        List<UncoveredFinding> uncovered = new ArrayList<>();
        List<String> blockingVetters = new ArrayList<>();
        List<VerdictState> effectiveStates = new ArrayList<>(run.verdicts().size());

        for (VettingRepository.VerdictView verdict : run.verdicts()) {
            List<Finding> residual = new ArrayList<>(verdict.findings().size());
            for (Finding finding : verdict.findings()) {
                Waiver waiver = firstCovering(candidates, finding, sha, now);
                if (waiver == null) {
                    residual.add(finding);
                } else {
                    suppressions.add(new Suppression(
                            verdict.vetter(),
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
                blockingVetters.add(verdict.vetter());
                for (Finding finding : residual) {
                    if (finding.severity().atLeast(Severity.HIGH)) {
                        uncovered.add(new UncoveredFinding(
                                verdict.vetter(),
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

        // A run that stopped early is blocked, whatever the waivers say (GW_VETTING_0032.3). This is
        // the rule the short-circuit mode stands or falls on. Without it, waiving the finding that
        // stopped the chain leaves one waived clearing verdict and a tail of vetters that never
        // looked — nothing blocking, one thing clearing — and the gate opens on evidence nobody
        // gathered, indistinguishable at the gate from a chain that ran to the end and found
        // nothing. The remedy a waiver is for is the *next* run: once the finding is suppressed the
        // chain does not stop there, and the gate is decided on a complete run. The unreached
        // vetters are already in blockingVetters, since NOT_REACHED does not clear, so the reviewer
        // reads "this needs another run" rather than "this needs another waiver".
        if (effectiveStates.contains(VerdictState.NOT_REACHED)) {
            effective = VettingChain.Outcome.BLOCKED;
        }
        return new Effect(effective, recorded, suppressions, uncovered, blockingVetters);
    }

    /**
     * Whether a verdict the chain has just produced still objects once the waivers active now are
     * applied — the question {@link ChainMode#STOP_AFTER_FAIL} has to ask before it stops
     * (GW_VETTING_0032).
     *
     * <p>The chain stops when a snapshot is <em>already condemned</em>, and a finding a reviewer has
     * accepted does not condemn it. Asking here is what keeps a waiver from becoming useless under
     * the stopping mode: without it, the same verdict would fail and stop the chain on every
     * subsequent run, so the fresh run the waiver is supposed to enable could never get further and
     * the gate would stay shut for good.
     *
     * <p>The same {@code Verdict.of} re-derivation the effective outcome uses, so what stops the
     * chain and what gates the approval cannot disagree about whether a verdict still objects.
     */
    @Requirements({"GW_VETTING_0032"})
    public static boolean stillObjects(Verdict verdict, List<Waiver> waivers, String sha, Instant now) {
        if (verdict.findings().isEmpty()) {
            return !verdict.state().clearing();
        }
        List<Finding> residual = verdict.findings().stream()
                .filter(finding -> firstCovering(waivers == null ? List.of() : waivers, finding, sha, now) == null)
                .toList();
        return !Verdict.of(residual).state().clearing();
    }

    /**
     * A verdict's state once its waived findings are removed. A verdict that already clears keeps
     * its state; one that does not, and has no findings to remove, keeps its state too — which is
     * how {@code PENDING} and an empty run stay blocking no matter what waivers exist.
     *
     * <p>{@link VerdictState#DISABLED} (GW_VETTING_0029) is handled separately rather than run through
     * {@link Verdict#of(List)} like every other state: it always carries an informational finding
     * so the disablement is visible on the run, and that finding's {@code INFO} severity would
     * otherwise re-derive to {@code PASS} on its own — an unwaived, purely bookkeeping finding
     * turning a switched-off vetter into positive clearing evidence it never produced, which is
     * exactly the blanket-approval loophole GW_VETTING_0029 forbids. Only when every finding on it has
     * actually been waived — residual empty, so something was named and accepted — does it clear,
     * the same "waiving everything clears the verdict" rule every other state gets.
     */
    private static VerdictState effectiveState(VettingRepository.VerdictView verdict, List<Finding> residual) {
        if (verdict.state().clearing() || verdict.findings().isEmpty()) {
            return verdict.state();
        }
        // A vetter the chain never reached stays not reached, whatever is waived. Its finding is
        // pure bookkeeping, and letting an accepted risk derive it to a PASS would turn a vetter
        // that did not look into positive clearing evidence — the same loophole DISABLED is guarded
        // against below, except that here there is nothing an administrator could even have meant
        // to accept (GW_VETTING_0032.2).
        if (verdict.state() == VerdictState.NOT_REACHED) {
            return VerdictState.NOT_REACHED;
        }
        if (verdict.state() == VerdictState.DISABLED) {
            return residual.isEmpty() ? VerdictState.PASS : VerdictState.DISABLED;
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
