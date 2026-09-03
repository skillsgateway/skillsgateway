# Design: approval-pending-webhook-event

## Context

See proposal.md — "Why". What the existing surface already fixes, and therefore
what this design has to fit inside rather than re-decide:

- **The event vocabulary is a compile-time constant.** `WebhookEvent.ALL` is the
  registry; `WebhookService.normalizeEvents` validates every subscriber filter
  against it and refuses an unknown name, and `GET /api/webhooks/events` serves
  it so the portal offers checkboxes rather than a text box. `audit.export` is
  deliberately outside `ALL`.
- **Emission is enqueue-only.** `WebhookService.emit` selects the enabled
  subscribers whose filter matches, serializes the payload **once**, and writes
  one `webhook_deliveries` row each. An administrative action never waits on a
  receiver. `WebhookDispatcher` polls, claims a due delivery with an atomic
  conditional update, POSTs it with an HMAC-SHA256 signature over the exact
  stored bytes, and retries with capped exponential backoff until the attempt
  budget is spent.
- **Vetting never changes snapshot state.** `VettingService.run` records a chain
  run and aggregates it fail-closed; the snapshot stays `held`. What gates an
  approval is not the recorded outcome but the *effective* one —
  `WaiverService.evaluate`, which layers the waivers active at that instant over
  the run (GW_0045).
- **Held is exactly the state that awaits a person.** `IngestionService` runs the
  chain for every snapshot it records as `held`, and only for those (a manifest
  violation is recorded `rejected` and never vetted). So "a chain run finished
  and the snapshot is held" is a complete and non-redundant characterization of
  "awaiting a human", with no new state machine and no new column.

## Goals / Non-Goals

**Goals:**

- One event that means "this snapshot is waiting for a person", carrying enough
  to triage without a follow-up API call, and enough to act on with the existing
  approve/reject endpoints.
- Shaped so #222 (operator-configurable external vetting connectors) can drive
  it later: the payload names the run and the connectors, so a connector that
  answers asynchronously has a correlation handle already on the wire.
- Zero effect on a subscriber that does not ask for it.

**Non-Goals:**

- Not a second delivery mechanism. No new ordering, retry or acknowledgement
  semantics; the event rides the existing dispatcher and inherits its contract
  exactly.
- Not an approval callback API. Approve and reject already exist and are
  unchanged; nothing about this event lets a receiver decide anything.
- Not #121. This change does not author an AsyncAPI document or a JSON-Schema
  pipeline for the webhook payload surface. See "Protecting the payload".
- Not #222. No configurable connector, no external verdict ingestion, no
  callback token.

## Decisions

### The emit point: after the chain run, when the snapshot is held

The issue offers two candidates — "entered held" at ingestion, or "vetting
complete, still held". This takes the second, in `VettingService.run` beside the
existing `SNAPSHOT_VETTED` emit, guarded by `Snapshot.HELD.equals(snapshot.state())`.

Reasons:

- At ingestion time there are no verdicts, so the payload could not carry them
  and a receiver would have to wait and poll — the exact cost the issue is
  trying to remove.
- Ingestion always vets a held snapshot, so nothing is missed by waiting for the
  run: every snapshot that reaches `held` produces exactly one chain run and
  therefore exactly one event.
- A re-vetting run against a still-held snapshot legitimately re-announces it:
  the verdicts changed, and the pending decision now has different evidence.
  Re-vetting an **approved** snapshot emits nothing new here — that is
  `snapshot.revet_violation`, which already exists and means something else.
- A `revoked` snapshot is also decidable, and deliberately does **not** emit
  this event: `snapshot.revoked` is the announcement for that transition, and
  re-using an "approval pending" event for a retraction would blur two facts a
  receiver has to keep apart.

The guard reads the snapshot state the chain was handed, which the chain is
contractually forbidden to change — so the condition cannot be stale in a way
that matters.

**Alternative considered:** emit from `IngestionService` after `vet(...)`
returns. Rejected: it would fire once per ingestion rather than once per run, so
a re-vet of a held snapshot would silently not re-announce, and the emit would
sit further from the data it reports.

### The payload: base fields plus a content-free vetting summary

The event body is the seven fields every lifecycle event already carries —
`event`, `occurredAt`, `marketplace`, `snapshotId`, `sha`, `state`, `actor` — in
the same names and positions, plus one nested object:

| `vetting` field | Meaning |
| --- | --- |
| `runId` | The chain run this event reports, for correlation with `GET /api/snapshots/{id}/vetting` |
| `outcome` | The **effective** outcome — the one that gates approval: `CLEAR`, `CLEAR_WITH_WAIVERS`, `BLOCKED` |
| `recordedOutcome` | What the connectors themselves concluded, before waivers |
| `blockingConnectors` | Names of the connectors that are the reason it blocks; empty when nothing objects |
| `uncoveredFindings` | How many blocking findings no active waiver covers |
| `waivedFindings` | How many findings an active waiver is currently suppressing |

Keeping the first seven fields identical is what makes the event free for an
existing receiver to adopt: a parser written for `snapshot.approved` reads this
payload unchanged and ignores one unknown key.

**What is deliberately absent, and why.** No finding messages, no rule ids, no
locations, no file names, no counts of files. A webhook target is
scheme-allowlisted, not an authenticated identity, and the whole point of
quarantine is that unapproved content does not leave it. A finding's `location`
is a path inside quarantined content and its `message` quotes what was found, so
both are content, not metadata — the trust boundary says the event *announces*
and the API *discloses*. `GET /api/snapshots/{id}/vetting` already serves the
full run to an authenticated caller, and the payload carries the `snapshotId`
and `runId` that address it. Counts and connector names are gateway metadata:
they are already in the approval-refusal problem document and on the ledger.

The effective outcome, not just the recorded one, is what a receiver needs:
`BLOCKED` means "waive or fix first", `CLEAR` means "an approve call will
succeed", `CLEAR_WITH_WAIVERS` means "it will succeed, and only because someone
accepted a risk". The vocabulary is `VettingChain.Outcome`'s, reused verbatim
from `GET /api/snapshots/{id}/vetting` rather than invented, and serialized in
the same upper-case form that endpoint serves.

**Alternative considered:** embed the verdicts and findings, as the issue's
"payload-rich" reading suggests. Rejected on the trust boundary above. The
counts give the same triage decision (act now / needs waivers) without putting
quarantined content on an unauthenticated wire.

### A typed emit variant rather than a general `emit(Object)`

`WebhookService.emit` keeps its exact signature and its exact payload record;
the fan-out (filter subscribers, serialize once, enqueue one row each) is
factored into a private helper that both paths call, so retries keep sending
byte-identical bodies for both.

The new path is `emitApprovalPending(...)`, typed to
`ApprovalPendingPayload`/`VettingSummary`, rather than a general
`emit(String event, Object payload)`. That is a security-shaped choice, not a
style one: an `Object` payload is an open door for a future caller to hand the
dispatcher something content-bearing, and the one rule this event must never
break is the one an open door makes easy to break by accident.

The payload records live in the `webhook` package next to the existing
`EventPayload`, and the two outcome fields are typed `String` on the wire
contract rather than `VettingChain.Outcome`. The `vetting` package already
depends on `webhook` (it emits); typing the payload with a vetting enum would
point the dependency both ways for no gain. `VettingService` converts at the
call site.

### Estate configuration

This adds **no** new API-managed runtime state, so `skills-gateway.estate.*`
needs no new object type — and the continuous obligation is still met, because
the runtime state that *references* the event is already estate-managed.

A subscribable event name is a compile-time constant in `WebhookEvent.ALL`, not
a row. The thing an operator creates is a subscriber, which
`skills-gateway.estate.webhooks` already reconciles (GW_0086) through
`WebhookService.register` → `normalizeEvents` — the same validation the API
uses, against the same registry. So
`events: snapshot.approval_pending,snapshot.approved` in a declared estate works
the day the constant exists, and a typo is still a reconciliation failure rather
than a silently dropped filter. A test covers exactly that, so the claim is
verified rather than asserted.

### Protecting the payload against accidental breakage

The gate this repository runs on the API contract is `oasdiff` over the
committed `openapi.json` (PR #242 raised `response-optional-property-removed` to
`err`, closing #216). It cannot see this payload, because webhook payloads
appear nowhere in the OpenAPI document: `WebhookService.EventPayload` is
annotated with `@Schema` but is not reachable from any `paths` entry, so
springdoc never emits it and oasdiff has nothing to diff. That gap is #121's
subject, and #121 has not yet chosen between an AsyncAPI document and generated
JSON Schemas.

This change therefore does two things and defers the third:

1. **Every always-present field carries `@Schema(requiredMode = REQUIRED)`** —
   on `ApprovalPendingPayload`, on `VettingSummary`, and on nothing that is
   genuinely optional. Records serialize every component, and this payload has
   no nullable field, so the annotation is honest. When #121 anchors the payload
   schema in a response — the route #216 records it as having chosen, precisely
   so response-side severities apply — the `required` array is already there and
   the gate bites on day one instead of needing a second pass.
2. **The field set is protected now, by a test in `./mvnw verify`.** A contract
   test asserts the exact top-level and nested key sets of a serialized payload.
   A removed or renamed field fails the build; an added one fails it too, until
   someone updates the test deliberately — which is the point, since a payload
   change should be a decision rather than a diff nobody noticed.
3. **No new REST endpoint is added to host the schema.** Anchoring the payload
   in a `paths` response would make oasdiff see it, but the only honest way to
   do that is to add or change an endpoint, and adding a permanent public
   endpoint whose purpose is to give a gate something to bite on is the wrong
   trade for one event out of nine. It would also decide #121's open question
   for it, and asymmetrically — one event in the contract document and eight
   not. Recorded here as the alternative considered, so #121 can take it up on
   the whole surface at once.

### Delivery semantics: inherited, not invented

Stated because a new event is exactly when a receiver author reads this:

- **Enqueue-only.** The emit writes one delivery row per matching subscriber and
  returns. A receiver that is down cannot slow or fail an ingestion.
- **At-least-once.** Non-2xx and transport errors are retried with capped
  exponential backoff until the attempt budget is spent; a process restart
  mid-attempt makes the delivery due again once its lease expires. Receivers
  de-duplicate on `X-Skills-Gateway-Delivery`.
- **No ordering guarantee, and one visible consequence.** The dispatcher claims
  due deliveries by id in batches, so ordering is neither promised nor
  achieved — and this event makes that concrete rather than theoretical:
  `snapshot.ingested` is emitted by the *caller* of `IngestionService.ingest`,
  which returns only after the chain has run, so `snapshot.approval_pending` is
  enqueued **before** `snapshot.ingested` for the same snapshot. A receiver must
  treat each event as self-describing. Documented in the guide.
- **Subscriber failure changes nothing else.** After the last attempt the
  delivery is `failed` and visible in `GET /api/webhooks/deliveries`; the
  snapshot stays held, and the only lost thing is the notification. A receiver
  that needs to reconcile reads the snapshot list and
  `GET /api/snapshots/{id}/vetting`.

## Risks / Trade-offs

- **A `*` subscriber starts receiving a new event** → That is what `*` means,
  and the payload is a superset of the shape such a subscriber already parses,
  so a well-written receiver ignores one key. Called out in the guide's event
  table. Filtering by name remains the way to opt out.
- **The payload is a new contract with no machine gate today** → Mitigated by
  the required-mode annotations plus the field-set contract test above; closing
  it properly is #121, which this change does not pre-empt.
- **Two events per held snapshot per run (`snapshot.vetted` and
  `snapshot.approval_pending`)** → Deliberate, and cheap: one delivery row each,
  only for subscribers that asked. `snapshot.vetted` keeps its meaning ("a run
  finished", for any state), so no existing receiver changes behavior.
- **The effective outcome is evaluated a moment after the run finishes** →
  `WaiverService.evaluate` reads the snapshot's latest run, which is the run just
  recorded except under a concurrent second run of the same snapshot. This is
  the same accepted race as `RevetService`, which evaluates the same way; the
  payload's `runId` is the run this event reports, and a receiver that needs the
  authoritative pairing re-reads it from the API.
- **A `held` snapshot ingested before this event existed never emits it** → It
  emits on its next chain run (a manual or scheduled re-vet). No backfill: a
  webhook is a notification of a transition, and manufacturing historical
  deliveries would be worse than the gap.
