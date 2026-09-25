# Design: sink-channel-delete-guard

## Context

See proposal.md, "Why". Relevant facts:

- `AuditExportService.createWebhookSink` creates a subscriber named after the
  sink, filtered to `audit.export`, then the `audit_sinks` row pointing at it. A
  sink never adopts an existing subscriber.
- `audit_sinks.subscriber_id … ON DELETE CASCADE`. `deleteSink` deletes the
  subscriber and relies on the cascade to remove the sink row. Its second delete
  is a no-op, and there is no transaction around the two.
- `WebhookController.delete` calls `WebhookService.deleteSubscriber`, a bare
  `DELETE FROM webhook_subscribers`.
- `EstateReconciler.reconcileWebhook` looks up a declared webhook by name and,
  when one exists, updates its URL, secret and events. The estate never deletes.
- `POST /api/v1/webhooks` cannot take a sink's name (the names are unique) and
  cannot subscribe to `audit.export` (it is not in the registry). Creation is
  therefore not a way in.

## Goals / Non-Goals

**Goals:** no path other than the sink's own removes or rewrites a sink's
channel. The refusal is explicit and names the sink. The database enforces the
invariant even if a future caller forgets the check.

**Non-Goals:**
- Changing how sinks deliver.
- Moving sink deliveries off the webhook machinery.
- Hiding sink channels from `GET /api/v1/webhooks`. The API keeps stating that
  the subscriber exists and marks it instead.

## Decisions

1. **Two layers: an application check for the message, the database for the
   guarantee.**
   - **Database.** `V1__init.sql` changes `audit_sinks.subscriber_id` to
     `ON DELETE RESTRICT`. Pre-1.0 the schema is one migration, so this edits
     `V1`. Every path that deletes a subscriber then fails on a sink's channel,
     including one not written yet.
   - **Application.** Before deleting, `WebhookController.delete` asks whether
     the subscriber is a sink's channel and answers `409` with a ProblemDetail
     naming the sink and `DELETE /api/v1/audit/sinks/{id}`. The same `409` is
     returned if the RESTRICT violation surfaces anyway, from a race with a sink
     created between the check and the delete. That race cannot actually occur,
     since a sink never adopts an existing subscriber, but the fallback costs one
     catch.
   - **Rejected alternative:** the application check alone. It protects only the
     callers that remember to make it.

2. **`deleteSink` deletes the sink row, then the channel, in one transaction.**
   RESTRICT reverses the order the cascade allowed. A `TransactionTemplate` makes
   the pair all-or-nothing: without it, a failure between the two deletes would
   leave an orphan subscriber, or an orphan sink under the old order. The
   comment on the method changes from "removing the subscriber cascades" to the
   new order.

3. **Estate: a declared webhook that collides with a sink's channel fails its
   entry.** `reconcileWebhook` checks whether the existing subscriber it found by
   name belongs to a sink. If it does, the entry fails with a validation failure
   naming the sink, and the declaration's other entries still reconcile: this is
   the existing `isolated(...)` behaviour. Nothing is written and no
   `webhook-subscriber-updated` is recorded.
   - **Rejected alternative:** treating the name clash as a start-up error for
     the whole estate. It is one bad entry, and the estate already reports those
     entry by entry.

4. **`auditSink` on `SubscriberView`: the owning sink's name, or null.** One
   `audit_sinks` read per list call, mapped by `subscriber_id`. It is nullable
   and not required, so the contract gate sees an addition.
   - **Rejected alternative:** a boolean `isAuditSink`. The name is what the
     portal links by, and it costs the same.

5. **Portal: filter by the field, not by the event filter.** Subscribers with
   `auditSink` set are left out of the **Subscribers** table.
   - **Delivery attempts:** a delivery whose subscriber is a sink channel shows
     "{name} · audit sink", linking to `/integrations/sinks`.
   - **Delete control:** there is none for those rows, because the rows are not
     there.
   - **Rejected alternative:** `events == ["audit.export"]`. It encodes an
     implementation detail the server has now stated outright.

## Risks / Trade-offs

- [Editing `V1` changes its checksum] → Every existing database is recreated.
  That is the project's pre-1.0 rule, and it is why no `V2` is added.
- [A client that relied on deleting a sink by deleting its subscriber] → That
  was the bug. The `409` names the call to use instead. Pre-1.0 this is not
  counted against the change.
- [`deleteSink` ordering under concurrent delivery] → A delivery in flight for a
  sink being removed already dies with the subscriber (its row cascades from the
  subscriber). The new order does not change that.

## Migration Plan

Schema edit to `V1` (recreate). Rollback is a revert.
