package dev.skillsgateway.server.policy;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Approval refused by the policy gate (GW_0090): at least one enabled rule matched, errored, or
 * could not see the facts. Thrown before the state transition, so nothing was decided and nothing
 * was published. Every deciding rule travels with the exception — a deny rule's exception path is
 * editing or disabling the rule itself, audited; there is no per-snapshot waiver.
 */
public class PolicyDeniedException extends RuntimeException {

    /** One deciding rule: {@code outcome} is {@code matched} or {@code error: <reason>}. */
    public record Denial(String rule, String outcome) {}

    private final transient List<Denial> denials;

    public PolicyDeniedException(long snapshotId, List<Denial> denials) {
        super(message(snapshotId, denials));
        this.denials = List.copyOf(denials);
    }

    public List<Denial> denials() {
        return denials;
    }

    private static String message(long snapshotId, List<Denial> denials) {
        String rules = denials.stream().map(Denial::rule).collect(Collectors.joining(", "));
        return ("snapshot %d cannot be approved: policy rules [%s] denied it. A deny rule has no per-snapshot"
                        + " override — an admin edits or disables the rule, audited, or the content changes upstream"
                        + " and is re-ingested.")
                .formatted(snapshotId, rules);
    }
}
