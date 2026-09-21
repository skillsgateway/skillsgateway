# Proposal: v1-schema-shape-corrections

## Why

`V1__init.sql` freezes at 1.0.0 and everything after it is a migration chain
forever. Four shape decisions in that file are wrong today, cost one line each
now, and cost a migration each afterwards. This is PR A of the 2026-09-20 1.0.0
readiness run — findings 8a to 8d, all approved.

Two of the four are the file contradicting its own recorded policy:

- `vetting_runs.trigger` is `TEXT` holding a closed set of three values the API
  already publishes as an enum, while `V1`'s header and the code-conventions skill
  both say enumerated values are native PostgreSQL enum types and never `TEXT`.
- `access_tokens.scopes` is comma-delimited **because**, in its own words, "Names
  cannot contain the delimiter (`^[a-z0-9][a-z0-9_-]*$`)" — a pattern that exists
  as a comment and not as a constraint.

## What Changes

### 1. `vetting_runs.trigger` becomes a native enum (8a)

`CREATE TYPE vetting_run_trigger AS ENUM ('ingestion', 'revet-scheduled',
'revet-manual')`, following the established `<singular table>_<column>` naming,
written through an explicit cast. The API already declares exactly these three
values, so nothing moves in the published contract.

### 2. `marketplaces.name` gains the `CHECK` its dependents assume (8b)

`CHECK (name ~ '^[a-z0-9][a-z0-9_-]*$')`.

**This is defence in depth, not a new refusal.**
`MarketplaceRegistrationService.MARKETPLACE_NAME:32` already validates the pattern
at registration and returns a proper API error, so no caller sees new behaviour.
What the constraint adds is that *every other* write path — the estate reconciler,
a future service method, a fixture — cannot introduce a name that silently breaks
the scope delimiter's stated assumption.

### 3. `access_tokens (principal)` gains an index (8c)

`TokenRepository:126` filters on `principal` for every token listing.

### 4. Every delimited column becomes `TEXT[]` (8d)

Five columns, not the four the review listed:

- `access_tokens.scopes`, `.push_scopes`, `.api_scopes`
- `webhook_subscribers.events`
- **`vetting_overrides.blocking_vetters`** (`V1__init.sql:514`, "a comma-separated
  vetter list") — found while scoping this change. Leaving it would ship a
  convention the same commit violates.

`vetting_chain_orders.vetters` (`:602`) is already `TEXT[]` and is the model,
including its `CHECK (cardinality(...) > 0)` where a non-empty list is required.

**The API does not move.** This is the finding that shaped the change: the token
API already publishes `scopes`, `pushScopes` and `apiScopes` as JSON arrays
(`TokenView`, `IssuedToken`, `CreateTokenRequest`), so for three of the five
columns the delimited form was never anything but an internal storage detail.
`webhook_subscribers.events` *is* published as a comma-delimited string
(`SubscriberView.events`, `CreatedSubscriber.events`,
`CreateSubscriberRequest.events`) — and that payload is **deliberately left
alone here**, split and joined at the controller boundary, so this change alters
no byte of `openapi.json`.

Changing that payload to an array is a breaking contract change. It belongs with
finding 9's enum-casing work in PR C, which is already a declared `!` release —
not bundled into the PR that rewrites the auth trust boundary's storage.
`EventRegistry.events` is already an array, so the string form is the odd one out
inside the current API too; PR C is where that gets settled.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None — this change sets `skip_specs: true`.

Every requirement keeps its exact meaning. `GW_AUTH_0006 — Marketplace-scoped
access tokens` governs what a scope *means*, not how a list of them is stored;
`GW_WEBHOOK_0001 — Snapshot lifecycle event webhooks` says delivery goes to every
subscriber "whose event filter includes that event" and is silent on the filter's
shape; the `trigger` values are unchanged and already published. There is no
requirement text to change, and inventing one to satisfy validation would be
worse than declaring the change spec-neutral.

## Impact

- **Schema**: `V1__init.sql` edited in place — one new enum type, one `CHECK`, one
  index, five columns retyped. Pre-1.0, so no `V2__…` behind it; the checksum
  changes and every existing database is recreated rather than migrated.
- **API**: none. `openapi.json` must come out byte-identical, and that is an
  assertion in the task list rather than a hope — the repo already has an
  `openapi.json` equality test to lean on.
- **Backend**: `AccessToken:33,63,99`, `WebhookSubscriber:21`,
  `WebhookService:310`, the repositories that write those columns, and the
  override-recording path for `blocking_vetters`.
- **Trust boundary.** `access_tokens` is the auth boundary, so
  `.claude/skills/old-coder` discipline applies to items 2 and 4: the tests that
  matter are the ones proving a scope list cannot *widen* through the conversion.
  Two cases carry the risk — `NULL` versus `'{}'`, which mean opposite things on
  `scopes` and must not be able to become each other, and a stored value
  containing the old delimiter.
- **Configuration**: none. `ConfigSurfaceBudgetTests` does not move.
- **The stop rule**: `capability-map.md` cannot absorb this — it describes
  capabilities, and these are corrections to how existing ones are stored. No
  backend package, estate object type, grantable role, scheduled sweep or
  configuration leaf is added.
- **Declarative estate (#65) does not extend.** No new API-managed runtime state;
  the objects these columns belong to are already estate-managed and their shapes
  as configuration do not change.
- **Docs**: `reference/api/tokens.md` and the webhook reference only if either
  describes storage rather than payload; the schema comments in `V1__init.sql`
  carry the real change. Confirm rather than assume — several pages quote the
  comma-delimited form.
- **Out of scope**: the query that motivated 8d — *which tokens can reach this
  marketplace*, one indexed `'acme' = ANY(scopes)`. It is a new API read on the
  auth trust boundary and gets its own change. This one only makes it possible.
