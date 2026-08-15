package io.github.jimisola.skillsgateway.approval;

import java.util.List;

/**
 * Approval refused because the snapshot's vetting chain blocked and the reviewer gave no reason
 * (GW_0041). Thrown before the state transition, so nothing was decided and nothing was published.
 */
public class VettingBlockedException extends RuntimeException {

    private final transient List<String> blockingConnectors;

    public VettingBlockedException(long snapshotId, List<String> blockingConnectors) {
        super(message(snapshotId, blockingConnectors));
        this.blockingConnectors = List.copyOf(blockingConnectors);
    }

    public List<String> blockingConnectors() {
        return blockingConnectors;
    }

    private static String message(long snapshotId, List<String> blockingConnectors) {
        String cause = blockingConnectors.isEmpty()
                ? "the vetting chain has not produced a clear outcome for it"
                : "the vetting connectors %s did not clear it".formatted(String.join(", ", blockingConnectors));
        return ("snapshot %d cannot be approved: %s. Supply an override reason to approve it anyway;"
                        + " the reason is recorded in the audit ledger.")
                .formatted(snapshotId, cause);
    }
}
