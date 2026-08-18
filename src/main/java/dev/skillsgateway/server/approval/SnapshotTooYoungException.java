package dev.skillsgateway.server.approval;

import java.time.Duration;

/**
 * Approval refused because the snapshot has not yet cleared the configured cooling-off window
 * (GW_0073). Thrown before the state transition, so nothing was decided and nothing was published.
 *
 * <p>The eligibility travels with the exception because "wait" is only actionable if it says how
 * long: the refusal names the setting that imposed the wait, the age the snapshot has reached, and
 * the time still to go, so neither a reviewer nor an operator has to work out which of the two is
 * required — patience, or a configuration change.
 */
public class SnapshotTooYoungException extends RuntimeException {

    private final transient ReleaseAgeGate.Eligibility eligibility;
    private final transient Duration minimum;

    public SnapshotTooYoungException(ReleaseAgeGate.Eligibility eligibility, Duration minimum) {
        super(message(eligibility, minimum));
        this.eligibility = eligibility;
        this.minimum = minimum;
    }

    public ReleaseAgeGate.Eligibility eligibility() {
        return eligibility;
    }

    /** The configured window, for the problem document. */
    public Duration minimum() {
        return minimum;
    }

    private static String message(ReleaseAgeGate.Eligibility eligibility, Duration minimum) {
        return ("snapshot %d cannot be approved yet: %s requires a minimum release age of %s, and the gateway"
                        + " first ingested this commit %s ago. It becomes approvable in %s, at %s — no action is"
                        + " needed for that to happen. The age is measured from the gateway's own first sighting"
                        + " of the commit, not from the commit's timestamp.")
                .formatted(
                        eligibility.snapshotId(),
                        ReleaseAgeGate.CONFIG_KEY,
                        ReleaseAgeGate.format(minimum),
                        ReleaseAgeGate.format(Duration.ofSeconds(eligibility.ageSeconds())),
                        ReleaseAgeGate.format(Duration.ofSeconds(eligibility.remainingSeconds())),
                        eligibility.eligibleAt());
    }
}
