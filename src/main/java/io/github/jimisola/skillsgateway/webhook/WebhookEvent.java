package io.github.jimisola.skillsgateway.webhook;

import java.util.List;

/**
 * The snapshot lifecycle events a subscriber can filter on (GW_0023).
 *
 * <p>The snapshot state machine is {@code held -> approved | rejected}; there is no
 * revocation or re-vet state today, so no {@code snapshot.revoked} event is defined.
 */
public final class WebhookEvent {

    public static final String SNAPSHOT_INGESTED = "snapshot.ingested";
    public static final String SNAPSHOT_APPROVED = "snapshot.approved";
    public static final String SNAPSHOT_REJECTED = "snapshot.rejected";

    /**
     * Retention deletions (GW_0032). They are lifecycle events like any other — the snapshot's
     * vetting state is unchanged by them, so the payload keeps reporting held or rejected, and the
     * actor distinguishes an operator from the scheduled policy pass.
     */
    public static final String SNAPSHOT_SOFT_DELETED = "snapshot.soft_deleted";

    public static final String SNAPSHOT_RESTORED = "snapshot.restored";

    /**
     * A vetting chain run finished (GW_0037). The payload reports the snapshot's own state, which
     * the chain never changes: what a receiver acts on is that fresh verdicts are now readable at
     * {@code /api/snapshots/{id}/vetting}.
     */
    public static final String SNAPSHOT_VETTED = "snapshot.vetted";

    public static final List<String> ALL = List.of(
            SNAPSHOT_INGESTED,
            SNAPSHOT_APPROVED,
            SNAPSHOT_REJECTED,
            SNAPSHOT_SOFT_DELETED,
            SNAPSHOT_RESTORED,
            SNAPSHOT_VETTED);

    /**
     * Event name carried by audit ledger export batches (GW_0028). Deliberately outside
     * {@link #ALL}: it is provisioned by creating an audit export sink, never by subscribing a
     * lifecycle receiver, and {@code WebhookService.emit} is never called with it — so not even a
     * {@code *} subscriber receives ledger content it did not ask for.
     */
    public static final String AUDIT_EXPORT = "audit.export";

    private WebhookEvent() {}
}
