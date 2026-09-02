package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;

/**
 * What a re-vetting run means for content that is already approved and served (GW_0050, GW_0052),
 * and the only place that judgement lives.
 *
 * <p>Approval and re-vetting ask different questions of the same evidence, and the difference is
 * the whole design of this class.
 *
 * <ul>
 *   <li><b>Approval</b> asks "may this be published?" and fails closed on everything: a failing
 *       connector, a crashed one, an absent run. Nothing is being served yet, so the cost of an
 *       over-strict answer is a delayed approval — cheap, reversible, and paid by one reviewer.
 *   <li><b>Re-vetting</b> asks "must this be retracted?" and that is not the same question. The
 *       content already carries a recorded approval decision made against positive evidence. The
 *       cost of an over-strict answer here is pulling live content out from under every consumer
 *       that fetched it.
 * </ul>
 *
 * <p>So an {@link VerdictState#ERROR} verdict — a connector that threw, timed out, or could not
 * read the snapshot — is deliberately <b>not</b> grounds for auto-quarantine. An error is evidence
 * about the scanner, not about the content: nothing in the snapshot changed, and a fleet-wide
 * connector outage would otherwise revoke an entire estate at once, turning a scanner bug into a
 * self-inflicted outage of exactly the content the gateway exists to serve. The same reasoning
 * covers {@link VerdictState#PENDING} ("has not answered yet") and a run in which no connector
 * objected to the content at all — an empty or misconfigured chain must never retract anything.
 *
 * <p>Nothing is weakened by this. Fail-closed still governs every path that <em>publishes</em>: an
 * inconclusive run leaves the recorded run blocked, so the snapshot cannot be approved, re-approved
 * or restored while the chain is broken, and an inconclusive re-vet is recorded and announced
 * rather than swallowed. The only thing declined is retracting already-approved content on evidence
 * that says nothing about that content.
 *
 * <p>Pure and static over data, like {@link VettingChain#aggregate} and {@link WaiverEvaluation}:
 * the rule that decides whether live content is pulled is exhaustively testable without a database,
 * a repository or a Spring context.
 */
public final class RevetVerdict {

    private RevetVerdict() {}

    /** What a re-vetting run concluded about already-approved content. */
    @Schema(description = "What a re-vetting run concluded about an approved snapshot")
    public enum Classification {

        /** Nothing objects, or only waivers stand between the run and clear. Nothing to do. */
        CLEAR,

        /**
         * The chain objects to the content itself, and no active waiver covers it: a retroactive
         * violation. In enforce mode this is what revokes and unpublishes the snapshot.
         */
        VIOLATION,

        /**
         * The run blocks, but only because the chain could not answer — a connector errored, timed
         * out, or has not answered. Recorded and announced; never grounds for revocation.
         */
        INCONCLUSIVE;

        public String stored() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean violation() {
            return this == VIOLATION;
        }
    }

    /**
     * Classifies one re-vetting run.
     *
     * <p>The recorded verdict states decide inconclusiveness, not the post-waiver ones. That is
     * deliberate: {@link Verdict#error} attaches a {@code CRITICAL connector-error} finding to an
     * error verdict, so re-deriving its state from residual findings would turn every unwaived
     * {@code ERROR} into a {@code FAIL} and undo the distinction this class exists to draw. A
     * waiver can only ever remove an objection, so a connector that still blocks after waivers and
     * whose recorded answer was {@code ERROR} is an error that is still blocking.
     *
     * @param run the run that was just recorded, or null when there is none
     * @param effect the effective outcome of that run with the active waivers layered over it
     */
    @Requirements({"GW_0050", "GW_0052"})
    public static Classification classify(VettingRepository.Run run, WaiverEvaluation.Effect effect) {
        if (effect == null || !effect.blocked()) {
            return Classification.CLEAR;
        }
        if (run == null) {
            // Blocked with no run to point at: nothing examined this content, so nothing objected
            // to it. That gates publication, as it always did; it does not retract.
            return Classification.INCONCLUSIVE;
        }
        List<String> blocking = effect.blockingConnectors();
        if (blocking.isEmpty()) {
            // A run with no verdicts at all — a chain configured to nothing — blocks by the
            // fail-closed aggregate, but has named no fault in the content.
            return Classification.INCONCLUSIVE;
        }
        for (String connector : blocking) {
            VerdictState recorded = run.stateOf(connector).orElse(VerdictState.ERROR);
            // ERROR and PENDING name no fault in the content; neither does DISABLED (GW_0143) — an
            // administrator switching a connector off says nothing about what it would have found,
            // so it must not be read as the chain objecting to the content.
            if (recorded != VerdictState.ERROR && recorded != VerdictState.PENDING && recorded != VerdictState.DISABLED) {
                return Classification.VIOLATION;
            }
        }
        return Classification.INCONCLUSIVE;
    }
}
