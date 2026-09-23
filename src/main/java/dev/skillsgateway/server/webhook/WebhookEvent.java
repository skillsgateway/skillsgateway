package dev.skillsgateway.server.webhook;

import io.github.reqstool.annotations.Requirements;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The lifecycle events a subscriber can filter on, and the body each of them delivers
 * (GW_WEBHOOK_0001, GW_WEBHOOK_0008, GW_VETTING_0016).
 *
 * <p>Every name lives under the subject it is about. {@code marketplace.snapshot.*} is what happened
 * to one snapshot of a marketplace; {@code marketplace.*} is what happened to the marketplace
 * itself. The namespace is the point: a flat vocabulary in which every name began with the only
 * subject there was left the second subject nowhere to go.
 */
public final class WebhookEvent {

    /**
     * Which body an event delivers. Read by the OpenAPI document to pick the schema each
     * {@code webhooks} entry references, so a new event cannot reach the contract without saying
     * what it puts on the wire.
     */
    public enum Shape {
        /** {@code WebhookService.EventPayload} — the seven snapshot fields. */
        SNAPSHOT,
        /** {@code WebhookService.ApprovalPendingPayload} — those seven plus the vetting summary. */
        APPROVAL_PENDING,
        /** {@code WebhookService.MarketplacePayload} — no snapshot to name. */
        MARKETPLACE
    }

    /** One event: its wire name and the body it carries. */
    public record Definition(String name, Shape shape) {}

    public static final String SNAPSHOT_INGESTED = "marketplace.snapshot.ingested";
    public static final String SNAPSHOT_APPROVED = "marketplace.snapshot.approved";
    public static final String SNAPSHOT_REJECTED = "marketplace.snapshot.rejected";

    /**
     * Retention deletions (GW_RETENTION_0002). They are lifecycle events like any other — the snapshot's
     * vetting state is unchanged by them, so the payload keeps reporting held or rejected, and the
     * actor distinguishes an operator from the scheduled policy pass.
     */
    public static final String SNAPSHOT_SOFT_DELETED = "marketplace.snapshot.soft_deleted";

    public static final String SNAPSHOT_RESTORED = "marketplace.snapshot.restored";

    /**
     * A vetting chain run finished (GW_VETTING_0001). The payload reports the snapshot's own state, which
     * the chain never changes: what a receiver acts on is that fresh verdicts are now readable at
     * {@code /api/snapshots/{id}/vetting}.
     */
    public static final String SNAPSHOT_VETTED = "marketplace.snapshot.vetted";

    /**
     * A re-vetting run found a violation in a snapshot that is already approved (GW_VETTING_0016) — the
     * retroactive one, the event that says content a team is already using has stopped being
     * acceptable.
     *
     * <p>Emitted in both modes, and this is the point of it: in warn mode it is the <em>only</em>
     * signal, because nothing else changes. The payload reports the snapshot's state at emit time,
     * so a receiver can tell "still served, act on it" (warn) from "already retracted" (enforce),
     * and {@code marketplace.snapshot.revoked} follows it when enforcement acted.
     */
    public static final String SNAPSHOT_REVET_VIOLATION = "marketplace.snapshot.revet_violation";

    /**
     * A snapshot was retroactively quarantined and is no longer served (GW_VETTING_0013, GW_VETTING_0016). The
     * counterpart of {@link #SNAPSHOT_APPROVED}: the ref that was advertised is gone, so a
     * consumer's next fetch of that marketplace will fail rather than quietly serve old content.
     */
    public static final String SNAPSHOT_REVOKED = "marketplace.snapshot.revoked";

    /**
     * A chain run finished against a snapshot that is still held: it is waiting for a person
     * (GW_WEBHOOK_0006). The event an external review pipeline subscribes to, and the reason it is not
     * {@link #SNAPSHOT_VETTED} — that one fires for every run in every state, including runs
     * against content that is already approved, and says nothing about a pending decision.
     *
     * <p>Emitted only while the snapshot is held. A retraction is announced by
     * {@link #SNAPSHOT_REVOKED}, which leaves a snapshot decidable again but means something a
     * receiver has to keep apart from a first review.
     */
    public static final String SNAPSHOT_APPROVAL_PENDING = "marketplace.snapshot.approval_pending";

    /** An upstream or hosted marketplace was registered (GW_WEBHOOK_0009). */
    public static final String MARKETPLACE_REGISTERED = "marketplace.registered";

    /**
     * A registered marketplace was changed (GW_WEBHOOK_0009). Today that is its ingestion trigger, the
     * only mutation the gateway offers; the name is the subject rather than the field so a later
     * mutation joins it instead of adding a near-synonym beside it.
     */
    public static final String MARKETPLACE_UPDATED = "marketplace.updated";

    /**
     * A vetter's enablement changed (GW_WEBHOOK_0009, GW_VETTING_0029.1) — which vetters run against a
     * marketplace's next ingestion. A gateway-wide toggle is announced too, with the {@code -}
     * marketplace the ledger uses for a global act.
     */
    public static final String MARKETPLACE_VETTER_TOGGLED = "marketplace.vetter_toggled";

    /**
     * A marketplace was removed (GW_WEBHOOK_0010). Its withdrawn snapshots are announced by
     * {@link #SNAPSHOT_REVOKED} as they go; this says the marketplace itself is gone.
     */
    public static final String MARKETPLACE_REMOVED = "marketplace.removed";

    /**
     * The one catalogue (GW_WEBHOOK_0008): the events a subscriber may filter on, in the order the
     * registry lists them, each paired with the body it delivers. The OpenAPI {@code webhooks}
     * entries, {@code GET /api/webhooks/events} and the filter validator all read this — an event
     * added here appears in all three, which is the drift the single list exists to close.
     */
    @Requirements({"GW_WEBHOOK_0008"})
    public static final List<Definition> CATALOGUE = List.of(
            new Definition(SNAPSHOT_INGESTED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_APPROVED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_REJECTED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_SOFT_DELETED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_RESTORED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_VETTED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_REVET_VIOLATION, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_REVOKED, Shape.SNAPSHOT),
            new Definition(SNAPSHOT_APPROVAL_PENDING, Shape.APPROVAL_PENDING),
            new Definition(MARKETPLACE_REGISTERED, Shape.MARKETPLACE),
            new Definition(MARKETPLACE_UPDATED, Shape.MARKETPLACE),
            new Definition(MARKETPLACE_VETTER_TOGGLED, Shape.MARKETPLACE),
            new Definition(MARKETPLACE_REMOVED, Shape.MARKETPLACE));

    public static final List<String> ALL =
            CATALOGUE.stream().map(Definition::name).toList();

    private static final Map<String, Shape> SHAPES =
            CATALOGUE.stream().collect(Collectors.toUnmodifiableMap(Definition::name, Definition::shape));

    /** The body {@code event} delivers; unknown names are a programming error, not a subscriber's. */
    public static Shape shapeOf(String event) {
        Shape shape = SHAPES.get(event);
        if (shape == null) {
            throw new IllegalArgumentException("'%s' is not a subscribable event".formatted(event));
        }
        return shape;
    }

    /**
     * Event name carried by audit ledger export batches (GW_AUDIT_0004). Deliberately outside
     * {@link #CATALOGUE}: it is provisioned by creating an audit export sink, never by subscribing a
     * lifecycle receiver, and {@code WebhookService.emit} is never called with it — so not even a
     * {@code *} subscriber receives ledger content it did not ask for. It is also the reason the
     * namespace is not simply stripped: {@code audit.export} is about the ledger, not a marketplace.
     */
    public static final String AUDIT_EXPORT = "audit.export";

    private WebhookEvent() {}
}
