# Proposal: approval-pending-webhook-event

## Why

Nothing on the outbound webhook surface says "a snapshot is waiting for a human
decision". `snapshot.ingested` fires before the chain has run and also for a
snapshot ingestion rejected outright; `snapshot.vetted` fires whenever a chain
run finishes — including a re-vetting run against content that is already
approved — and its payload carries no verdict information at all. An external
review system (a ticketing system, a change-approval board, a bot) that wants to
drive `POST /api/snapshots/{id}/approve|reject` therefore has to infer the
"needs a review" state by correlating two events and polling
`GET /api/snapshots/{id}/vetting`.
Issue [#238](https://github.com/skillsgateway/skillsgateway/issues/238) asks for
the fact to be a first-class event, so an organization can curate the gateway
from the approval process it already runs without changing how it reviews things.

This is also the first step of a chain — #238 → #239 → #237 →
[#222](https://github.com/skillsgateway/skillsgateway/issues/222)
(operator-configurable external vetting connectors) — so the event is shaped to
be something #222 can later drive, without building any of #222 here.

## What Changes

- **A new subscribable lifecycle event, `snapshot.approval_pending`
  (GW_0159).** Emitted when a vetting chain run finishes against a snapshot that
  is `held` — "vetting is complete and it is still waiting for a human" — beside
  the existing `snapshot.vetted` emit, and never for a snapshot in any other
  state. It joins `WebhookEvent.ALL`, so it is filterable, offered by
  `GET /api/webhooks/events`, and declarable in `skills-gateway.estate.webhooks`
  through the existing event-filter validation.
- **A payload-rich emit path (GW_0160).** The event carries the seven fields
  every lifecycle event already carries plus a `vetting` object: the run id, the
  effective and recorded outcomes, the names of the blocking connectors, and how
  many findings are uncovered and how many are waived. Counts and connector
  names only — no finding messages, no locations, no file names, nothing derived
  from the quarantined content itself. A receiver that needs the detail reads it
  from `GET /api/snapshots/{id}/vetting` as an authenticated caller.
- **Additive, and inert for existing subscribers.** No existing event's shape or
  meaning changes; a subscriber whose filter does not name the new event receives
  nothing new. Delivery keeps the dispatcher's existing contract (enqueue-only,
  at-least-once, no ordering guarantee).
- Requirements GW_0159 and GW_0160, with SVC_GW_0159 and SVC_GW_0160.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `lifecycle-webhooks`: the event vocabulary gains a first-class
  approval-pending event, and the emit surface gains a payload that carries a
  content-free vetting summary rather than only snapshot identifiers.

## Impact

- **DB**: none. No schema change — deliveries are already event-agnostic
  (`event` is a text column, the payload an already-serialized string).
- **Backend**: `WebhookEvent` (new constant, added to `ALL`), `WebhookService`
  (new `ApprovalPendingPayload`/`VettingSummary` records and an
  `emitApprovalPending` variant; the existing `emit` keeps its signature and its
  payload shape), `VettingService` (emits it after the chain run when the
  snapshot is held; gains a `WaiverService` dependency for the effective
  outcome).
- **API**: `GET /api/webhooks/events` gains one entry — purely additive to a
  `string[]` response. No new endpoint, no changed endpoint.
- **Estate**: no new API-managed runtime state. The subscriber rows that
  reference the event are already reconciled by `skills-gateway.estate.webhooks`
  (GW_0086), and their `events` filter is validated against the same registry,
  so the new event is declarable the day it exists — see design.md, "Estate
  configuration".
- **Trust boundary**: the event announces that unapproved content exists; it must
  not become a way to read it. `ApprovalService` stays the only publisher, and
  the payload is identifiers, counts and connector names only.
- **Docs** (same PR): `guides/lifecycle-webhooks.md`, which is both the guide and
  the reference for this surface — the event table, the payload section, and the
  ordering caveat. The portal's event checkboxes are rendered from
  `GET /api/webhooks/events`, so `reference/portal.md` needs no change.
