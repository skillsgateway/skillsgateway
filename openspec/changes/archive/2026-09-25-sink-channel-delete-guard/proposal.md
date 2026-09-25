# Proposal: sink-channel-delete-guard

## Why

An audit export sink delivers through an ordinary webhook subscriber:
`audit_sinks.subscriber_id` references `webhook_subscribers (id) ON DELETE CASCADE`,
and the subscriber carries the sink's name and the `audit.export` filter (#486).
Nothing marks that subscriber as belonging to a sink, so two surfaces that manage
webhook subscribers can reach it:

- **The webhooks API and portal.** The Webhooks page lists the channel beside
  lifecycle subscribers, badged *unknown event*. That makes it look like a stale,
  broken subscriber, and its **Delete** calls `DELETE /api/v1/webhooks/{id}`. The
  cascade then removes the audit sink, and audit export stops without anyone
  having asked for that.
- **The declarative estate.** A declared webhook whose name equals a sink's name
  reconciles onto the sink's channel and overwrites its URL, secret and event
  filter.

This is a data-loss path to the compliance feed, reachable in two clicks by an
administrator who is tidying up.

## What Changes

- **A sink's delivery channel is managed only through its sink.**
  - `DELETE /api/v1/webhooks/{id}` refuses such a subscriber with `409 Conflict`,
    names the sink, and points to `DELETE /api/v1/audit/sinks/{id}`.
  - Estate reconciliation refuses a declared webhook that would update such a
    subscriber. The entry fails, and the other entries still reconcile.
  - Nothing is deleted, changed or recorded as changed.
- **The subscriber listing says which subscribers are sink channels.**
  `SubscriberView` gains `auditSink`, the owning sink's name, which is absent for a
  lifecycle subscriber. This is an additive field.
- **The portal stops presenting a sink as a subscriber.**
  - The Webhooks page leaves sink channels out of **Subscribers**.
  - In **Delivery attempts**, their rows read as audit-sink deliveries and link
    to Audit sinks, so a failing sink stays visible where deliveries are.

No schema change. Removing a sink through the audit sinks API is unchanged, and
still takes its channel with it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `lifecycle-webhooks`: GW_WEBHOOK_0004 — Portal webhook administration is
  revised: the subscribers listed are lifecycle subscribers, and a sink's
  deliveries are labelled as such. Adds GW_WEBHOOK_0011 — An audit sink's delivery
  channel is changed only through its sink.

## Impact

- **Server:**
  - `WebhookController` (the delete refusal and the `auditSink` field on the
    view).
  - `WebhookService` or `AuditSinkRepository` (a lookup from subscriber to sink).
  - `EstateReconciler.reconcileWebhook` (the refusal).
- **API contract:** `openapi.json` and `types.gen.ts` are regenerated. The
  changes are additive: a response field and a documented `409`.
- **Portal:** `pages/webhooks.tsx` and its tests.
- **Tests:** negative tests, shown to fail before the fix, cover:
  - the API delete, including that the sink survives and nothing is recorded;
  - the estate collision, including that the channel is unchanged and the other
    entries still apply;
  - the portal filtering and labelling.
- **Docs:** the webhooks API reference, the portal reference (Webhooks), and the
  lifecycle-webhooks and audit export guides.
- **reqstool:** GW_WEBHOOK_0004 is revised; GW_WEBHOOK_0011 is added with its SVC.
- **Stacked on #485:** the portal change edits the Webhooks page layout that #485
  introduces and links to its `/integrations/sinks`.
