package dev.skillsgateway.server.approval;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Approval refused because a plugin name the snapshot introduces collides with one the approved
 * estate already carries in another marketplace, and no active waiver covers it (GW_APPROVAL_0019).
 * Raised before the transition takes effect, so nothing was decided and nothing was published.
 */
public class NameCollisionException extends RuntimeException {

    private final long snapshotId;
    private final transient List<NameCollisionGate.Collision> collisions;

    public NameCollisionException(long snapshotId, List<NameCollisionGate.Collision> collisions) {
        super(("snapshot %d cannot be approved: %s. Plugin names are compared after folding case, separators and"
                        + " lookalike characters. If the name is a legitimate fork rather than an impersonation,"
                        + " record a waiver on rule %s for this snapshot and approve again.")
                .formatted(snapshotId, describe(collisions), NameCollisionGate.RULE_ID));
        this.snapshotId = snapshotId;
        this.collisions = List.copyOf(collisions);
    }

    public long snapshotId() {
        return snapshotId;
    }

    public List<NameCollisionGate.Collision> collisions() {
        return collisions;
    }

    /** One clause per colliding name, naming its incumbents; also the ledger detail. */
    static String describe(List<NameCollisionGate.Collision> collisions) {
        return collisions.stream()
                .map(collision -> "plugin '%s' at %s collides with %s"
                        .formatted(
                                collision.pluginName(),
                                collision.location(),
                                collision.incumbents().stream()
                                        .map(incumbent -> "'%s' in marketplace %s (snapshot %d)"
                                                .formatted(
                                                        incumbent.pluginName(),
                                                        incumbent.marketplace(),
                                                        incumbent.snapshotId()))
                                        .collect(Collectors.joining(", "))))
                .collect(Collectors.joining("; "));
    }
}
