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

    public static final List<String> ALL = List.of(SNAPSHOT_INGESTED, SNAPSHOT_APPROVED, SNAPSHOT_REJECTED);

    /**
     * Event name carried by audit ledger export batches (GW_0028). Deliberately outside
     * {@link #ALL}: it is provisioned by creating an audit export sink, never by subscribing a
     * lifecycle receiver, and {@code WebhookService.emit} is never called with it — so not even a
     * {@code *} subscriber receives ledger content it did not ask for.
     */
    public static final String AUDIT_EXPORT = "audit.export";

    private WebhookEvent() {}
}
