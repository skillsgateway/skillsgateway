# Webhook event registry

## Why

Registering a webhook subscriber means typing its event filter into a free-text
box. The server does validate it — an unknown name is a 400 naming the known
events — so nothing is silently dropped. The cost is paid in the form instead:
the only way to learn the vocabulary is to guess, submit, and read the error.

That collides with a hard rule in the portal's own conventions: *"a control that
the server would reject must never be pressable — press it and read the toast is
not a validation strategy"*. The Events field cannot satisfy it, because the
client has no way to know the vocabulary. Its own code comment concedes exactly
this, filing the event registry under the conventions' escape hatch for
"a rule the client cannot know".

Serving the registry removes the escape hatch rather than relying on it. The
vocabulary is closed, server-owned, and already enumerated in
`WebhookEvent.ALL`; it is simply not reachable from the browser. Eight events
live there today and the set has grown with retention, re-vetting and
revocation, so a list duplicated into the portal would already be four events
stale.

`audit.export` is deliberately *outside* that set: it is provisioned by creating
an audit export sink, never by subscribing a lifecycle receiver. Serving the
registry from the server is what keeps that distinction correct in the UI by
construction rather than by comment.

## What Changes

- **`GET /api/webhooks/events`** answers the lifecycle event registry — exactly
  `WebhookEvent.ALL`, in dispatch-meaningful order, never `audit.export`. Read-only,
  recording nothing, gated like the other webhook reads (auditor).
- **The portal composes the filter instead of accepting prose**: the Events field
  becomes a checkbox list built from that endpoint, with a select-all control and a
  type-ahead filter for narrowing it. The submitted value keeps the existing wire
  format — comma-delimited names, or `*` when every event is selected — so no
  server-side parsing changes and existing subscribers render unchanged.
- **A subscriber whose stored filter names an event the registry no longer
  contains is shown as such**, rather than rendered as an ordinary filter.

## Impact

- Additive endpoint; no change to the subscriber wire format, the dispatcher, or
  any stored data.
- `lifecycle-webhooks` spec gains GW_0088.
