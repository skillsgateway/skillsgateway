## Why

The lifecycle webhook vocabulary is nine flat `snapshot.*` names plus
`audit.export`. Every event the gateway can send is about a snapshot, so the
moment an event is about the *marketplace* — it was registered, its sync mode
changed, a vetter was switched on for it — there is nowhere for that name to
live without either colliding with the snapshot names or sitting beside them as
a second, unrelated top level. Pre-1.0 the namespace costs one rename; after 1.0
it costs a major.

The second half of issue #393 asks for a webhook per facade fetch. That is the
wrong tool and this change says so in the documentation rather than in code:
fetch volume is high, the ledger already records every fetch, and an audit sink
already pushes signed, retried ledger batches to a receiver. "Someone fetched X"
is derivable from a sink today.

## What Changes

- **BREAKING.** The nine subscribable events are renamed from `snapshot.<x>` to
  `marketplace.snapshot.<x>`. The event name is part of the published payload
  contract (the OpenAPI document's top-level `webhooks` object, the
  `X-Skills-Gateway-Event` header and the body's `event` field), so this moves
  under the contract rules in `docs/manual/reference/compatibility.md` — the
  pull request declares the break in its title and carries the
  `⚠️ BREAKING CONTRACT` label. `audit.export` is untouched: it is not
  subscribable and is not a lifecycle event.
- Stored subscriber event filters are rewritten in place by a Flyway migration,
  so an existing deployment keeps receiving what it asked for instead of
  silently going quiet.
- Three new marketplace-level events — `marketplace.registered`,
  `marketplace.updated`, `marketplace.vetter_toggled` — emitted from the same
  methods that write the corresponding ledger entries, carrying a payload of
  their own (there is no snapshot to name).
- `marketplace.removed` from the issue is **not** implemented and the reason is
  recorded: the gateway has no deregistration path — there is no
  `DELETE /api/marketplaces/{name}`, by design — so the event would have no
  emitter. It is added when deregistration is.
- Per-fetch / adoption events are **not** implemented. The webhooks guide gains
  a short note naming the audit sink as the answer, pointing at
  `docs/manual/guides/exporting-the-audit-ledger.md`.

## Capabilities

### New Capabilities

None. Both requirements belong to the existing lifecycle webhook capability.

### Modified Capabilities

- `lifecycle-webhooks`: adds GW_WEBHOOK_0008 — Namespaced lifecycle event names
  and GW_WEBHOOK_0009 — Marketplace administration event webhooks.

## Impact

- `WebhookEvent` (the event catalogue), `WebhookService` (a second payload
  record and the emit that builds it), `WebhookController` (the registry
  response and its examples), `OpenAPI` (the generated `webhooks` entries, which
  now pick a body schema per event).
- New emitters in `MarketplaceRegistrationService`, `SyncService` and
  `VetterToggleService`.
- A new Flyway migration `V3` rewriting `webhook_subscribers.events`; `V1` and
  `V2` are not edited.
- The committed `src/main/frontend/openapi.json` and the generated
  `src/api/types.gen.ts`, which is the baseline the breaking-change gate diffs
  against.
- Portal test fixtures and MSW handlers that spell event names; the portal page
  itself renders the registry the API serves and needs no change.
- Documentation: the event table and new sections in
  `docs/manual/guides/lifecycle-webhooks.md`, the estate `events` examples in
  `docs/manual/guides/declarative-estate.md` and
  `docs/manual/reference/configuration.md`, and every other page that spells an
  event name.
