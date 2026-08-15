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

    private WebhookEvent() {}
}
