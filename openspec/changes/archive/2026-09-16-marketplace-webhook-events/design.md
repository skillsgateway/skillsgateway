## Context

See proposal.md — Why.

What shapes the approach is that one catalogue already drives three surfaces.
`WebhookEvent.ALL` is looped by `OpenAPI.lifecycleWebhookDeliveries()` to
generate the document's top-level `webhooks` entries, served verbatim by
`GET /api/webhooks/events`, and checked by `WebhookService.normalizeEvents` when
a subscriber registers a filter. An event added to that list cannot fail to
appear in the contract. The one place the catalogue is not enough today is the
body: `OpenAPI.delivery` picks between two component names with an equality test
against `SNAPSHOT_APPROVAL_PENDING`. A third body shape turns that special case
into a lookup, which is the only structural change the seam needs.

Constraints that are not negotiable here:

- Event names are contract (`docs/manual/reference/compatibility.md` — The API
  contract). Renaming them is breaking and is declared, not smuggled.
- A webhook target is authorised by a URL scheme allowlist, not by an identity,
  so no payload may carry content out of quarantine.
- Stored filters are exact-match strings in `webhook_subscribers.events`. A
  rename that does not rewrite them silently stops delivering.

## Goals / Non-Goals

**Goals:**

- One catalogue that names every subscribable event and says which body it
  delivers, with the OpenAPI document and the events endpoint both derived from
  it.
- An existing deployment's subscribers keep receiving what they asked for
  across the rename, with no operator action.
- Marketplace-level events emitted from the same methods that write the ledger
  entry, so an emission and a ledger row cannot disagree about whether the
  action happened.

**Non-Goals:**

- A generated documentation table. The event table in the webhooks guide stays
  hand-written; there is no docs generator in this repository and inventing one
  for one table is not warranted.
- A general `emit(String, Object)`. The typed-per-shape emit is a security
  choice already made for `emitApprovalPending` and is kept.
- Backward-compatible dual names. Accepting both `snapshot.approved` and
  `marketplace.snapshot.approved` would give the two spellings time to drift,
  which is the reason V2 gave for renaming rather than aliasing.

## Decisions

### The catalogue gains a shape, not a second list

`WebhookEvent` keeps its public `String` constants — they are what call sites
pass — and gains a `Shape` enum (`SNAPSHOT`, `APPROVAL_PENDING`, `MARKETPLACE`)
plus a `CATALOGUE` of `(name, shape)` pairs. `ALL` becomes the names derived
from `CATALOGUE`, so it stays the `List<String>` the registry response and the
filter validator already use, and `shapeOf(name)` is what `OpenAPI.delivery`
asks instead of comparing against one constant.

*Alternative considered:* an enum whose constants are the events, with the wire
name as a field. Rejected: every call site passes a `String` today and every
stored filter is a `String`; converting them is a large diff that buys nothing
the record pair does not, and it would put wire names on `enum` constant names
where a rename becomes a refactor rather than a one-line edit.

### The marketplace body is its own record, sharing the first four fields

`MarketplacePayload(event, occurredAt, marketplace, actor, detail)` — the four
fields every event carries, in the same names and order as `EventPayload`, plus
one. `snapshotId`, `sha` and `state` are absent because a marketplace event has
no snapshot; inventing a sentinel for them would make a receiver parse a value
that means nothing.

`detail` is a short, structured, content-free string mirroring the ledger
entry's own detail — `origin=upstream`, `mode=webhook`,
`vetter=secret-scan enabled=false`. It deliberately omits the operator-supplied
`reason` a vetter toggle may carry: free text in a payload that leaves the
gateway is exactly the field that later carries something nobody meant to send.
The ledger keeps the reason and an authenticated caller reads it there — the
same "the event announces, the API discloses" rule the approval-pending payload
already follows.

*Alternative considered:* reusing `EventPayload` with `snapshotId = 0`. Rejected
for the reason above, and because the contract marks every one of its fields
required — a field that is always zero is a lie the schema would have to tell.

### `marketplace.vetter_toggled` reports a global toggle as `-`

A vetter can be switched off globally, which affects every marketplace. That is
the more important signal of the two, so it is emitted, with `marketplace` set
to the `-` placeholder the ledger's NOT NULL column already uses for
gateway-wide events. A receiver distinguishing the two reads that field. The
alternative — emitting only marketplace-scoped toggles — would drop the case an
operator most wants to hear about.

### `marketplace.removed` is not implemented

The gateway has no deregistration path: there is no `DELETE` or `PUT
/api/marketplaces/{name}`, the estate reconciler is additive by design, and
`docs/manual/concepts/trust-boundaries.md` states that a declaration can create
and converge, never deregister. There is no ledger entry to emit beside, so the
event would have no emitter and its `webhooks` entry would document a delivery
that can never arrive. It is added when deregistration is.

### No per-fetch events; the audit sink is the answer

Recorded here so the next reader does not re-open it: fetch volume is high, the
ledger records every fetch already, and an audit export sink pushes signed,
retried batches of those entries to a receiver. A per-fetch webhook would be a
second, weaker delivery path for data a sink already carries. The webhooks guide
says so and links the sink guide; nothing in code changes.

### The stored filters are rewritten by `V3`

A Flyway migration rewrites `webhook_subscribers.events`, replacing the
`snapshot.` prefix with `marketplace.snapshot.`. `V1` and `V2` are not edited —
`V2` exists, so the single-migration convention in the code-conventions skill is
already retired for this schema, and editing an applied migration would not
reach a deployed database anyway. `*` and `audit.export` do not contain the
prefix and are untouched.

## Risks / Trade-offs

- **A receiver written against the old names stops receiving anything** → That
  is what the rename means, and it is the one thing the contract rules exist to
  make visible: the pull request declares the break in its title and carries the
  `⚠️ BREAKING CONTRACT` label, and the guide's event table is the migration
  map. Pre-1.0 this is the cheapest it will ever be.
- **The filter migration is a string substitution** → Narrow by construction:
  no current event name contains `snapshot.` other than as its leading segment,
  and `audit.export` and `*` do not contain it at all. A test asserts a stored
  filter survives the rename.
- **A marketplace event's `detail` could grow into a content channel** → It is
  built at the emit site from gateway-side configuration values only, never from
  snapshot content, and the field is documented as such. The `reason` exclusion
  is the precedent.
- **Three new emitters mean three new ways an admin action could fail because of
  a receiver** → It cannot: emission is enqueue-only, and each new call sits
  after the ledger write on the success path, so a failed action reaches no emit
  at all. Negative tests assert exactly that.

## Migration Plan

One deployment. Flyway `V3` runs at startup and rewrites stored filters before
any event is emitted under the new names. There is no window in which a
subscriber's filter names an event the gateway no longer sends: the old names
stop existing in the same release that rewrites the filters. Rollback is a
redeploy of the previous version, whose `V1`/`V2` are unchanged; the rewritten
filters would then match nothing until edited, which is the ordinary cost of
rolling back across a declared breaking change.
