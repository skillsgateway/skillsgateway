package dev.skillsgateway.server.approval;

import dev.skillsgateway.server.vetting.WaiverEvaluation;
import java.util.List;

/**
 * Approval refused because the snapshot's effective vetting outcome is blocked (GW_APPROVAL_0003): its
 * latest chain run objects, and at least one blocking finding is not covered by an active waiver.
 * Thrown before the state transition, so nothing was decided and nothing was published.
 *
 * <p>The uncovered findings travel with the exception because they are the reviewer's worklist:
 * they are exactly the set of waivers that must exist for the approval to succeed. Naming them is
 * what turns "cover every blocking finding" from a guessing game into an actionable refusal.
 */
public class VettingBlockedException extends RuntimeException {

    private final transient List<String> blockingVetters;
    private final transient List<WaiverEvaluation.UncoveredFinding> uncoveredFindings;

    public VettingBlockedException(
            long snapshotId,
            List<String> blockingVetters,
            List<WaiverEvaluation.UncoveredFinding> uncoveredFindings) {
        super(message(snapshotId, blockingVetters, uncoveredFindings));
        this.blockingVetters = List.copyOf(blockingVetters);
        this.uncoveredFindings = List.copyOf(uncoveredFindings);
    }

    public List<String> blockingVetters() {
        return blockingVetters;
    }

    /** The blocking findings no active waiver covers; empty when the chain never produced any. */
    public List<WaiverEvaluation.UncoveredFinding> uncoveredFindings() {
        return uncoveredFindings;
    }

    private static String message(
            long snapshotId,
            List<String> blockingVetters,
            List<WaiverEvaluation.UncoveredFinding> uncoveredFindings) {
        String cause = blockingVetters.isEmpty()
                ? "the vetting chain has not produced a clear outcome for it"
                : "the vetters %s did not clear it".formatted(String.join(", ", blockingVetters));
        String uncovered = uncoveredFindings.isEmpty()
                ? ""
                : " Uncovered findings: %s."
                        .formatted(uncoveredFindings.stream()
                                .map(finding -> "%s at %s".formatted(finding.ruleId(), finding.location()))
                                .collect(java.util.stream.Collectors.joining(", ")));
        return ("snapshot %d cannot be approved: %s.%s Record a scoped, expiring waiver for each blocking"
                        + " finding — with a justification and an expiry — and approve again.")
                .formatted(snapshotId, cause, uncovered);
    }
}
