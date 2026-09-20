# Design: v1-schema-shape-corrections

## Context

See `proposal.md — Why`. The design-level facts that constrain the approach:

- **The array idiom already exists in this codebase**, in
  `VettingChainSettingsRepository`: write an array-literal string bound to a
  `:vetters::text[]` cast (`:54-60`, helper at `:163-174`), read with
  `rs.getArray(...)` then `(String[]) array.getArray()` (`:141-142`). The helper is
  `private`, so following it means either extracting it or copying it into three
  more repositories.
- **The delimited form has a live footgun.** `Arrays.asList(scopes.split(","))`
  (`AccessToken:33`) turns the empty string into `[""]` — a list of one empty
  name — rather than an empty list. A stored `''` therefore reads today as a token
  scoped to a marketplace that cannot exist.
- **Two existing `CHECK`s treat `api_scopes IS NOT NULL` as "this is a machine
  credential"** (`session_derived_credentials_hold_no_api_scope` at `:293`,
  `machine_credentials_expire` at `:299`).
- The API already publishes the three token scope fields as JSON arrays, and
  `webhook_subscribers.events` as a comma-delimited string.

## Goals / Non-Goals

**Goals.** Storage shape that the database can constrain and query. Byte-identical
`openapi.json`. No path by which a scope list widens.

**Non-Goals.** The `'acme' = ANY(scopes)` query and its endpoint — its own change.
The `events` *payload* shape, which is PR C. Any change to what a scope means.

## Decisions

### 1. The empty array is forbidden, per column, in the database

This is the load-bearing decision and the reason this change touches the auth
boundary rather than being a refactor.

`TEXT[]` introduces a value the delimited form could not cleanly express: the
empty array. It is not a harmless synonym for `NULL`, because on these columns
`NULL` carries meaning — and on one of them the two would disagree dangerously:

| Column | `NULL` means | Empty array would mean | Verdict |
| --- | --- | --- | --- |
| `scopes` | every marketplace | no marketplace — the opposite | forbid |
| `push_scopes` | no push | no push — same thing | forbid anyway, for one rule |
| `api_scopes` | not a machine credential | **a machine credential holding nothing** | forbid |
| `events` | `NOT NULL` already | a subscriber matching no event | forbid |
| `blocking_vetters` | nothing was blocking | nothing was blocking | forbid anyway |

`api_scopes` is the sharp one. An empty array satisfies `IS NOT NULL`, so it would
make a credential *count* as a machine credential — forced to expire, barred from
being session-derived — while granting no administrative reach at all. The two
existing `CHECK`s would still pass while meaning something they were not written
to mean.

So every one of the five columns gets
`CHECK (<col> IS NULL OR cardinality(<col>) > 0)`, and `events`, being `NOT NULL`,
gets the plain `CHECK (cardinality(events) > 0)` that
`vetting_chain_orders.vetters` already carries. One rule across all five, so no
reader has to remember which column tolerates what.

This also closes the `''` footgun by construction: the state that reads as `[""]`
today becomes unrepresentable rather than merely unused.

### 2. `arrayLiteral` is extracted, not copied

Four repositories now need it. It moves from `VettingChainSettingsRepository` into
the existing `persistence` package as a small package-visible helper — no new
package, per the stop rule — and the original call site uses the extracted one, so
there is exactly one implementation of the escaping.

The escaping matters: the helper quotes every element and escapes `\` and `"`. A
second hand-rolled copy is how one of them ends up not doing that.

*Alternative considered:* `connection.createArrayOf("text", …)` through
`JdbcClient`. Rejected — it needs the connection, which `JdbcClient`'s fluent API
does not hand out, and the literal-plus-cast form is what the codebase already
does and what its tests already cover.

### 3. `webhook_subscribers.events` splits and joins at the controller boundary

The column becomes `TEXT[]`; the request and response payloads keep the
comma-delimited string. The join and split live at the single controller/service
boundary, not scattered.

This is deliberately a temporary asymmetry. It buys a PR A that changes no byte of
the published contract, which matters because PR A also rewrites how the auth
boundary stores its scopes — bundling a declared contract break into that PR would
put two unrelated risks in one review. PR C settles the payload, where the other
breaks live, and `EventRegistry.events` already being an array is the evidence
that the string is the outlier rather than the intent.

`'*'` keeps meaning every event, as `['*']`. Changing the wildcard's
representation at the same time as the column's would be two semantic changes
wearing one diff.

### 4. The enum follows the existing cast idiom exactly

`CREATE TYPE vetting_run_trigger AS ENUM ('ingestion', 'revet-scheduled',
'revet-manual')`, written as `:trigger::vetting_run_trigger`, read unchanged as a
`String`. The code-conventions skill records the permanent trade this accepts — a
value cannot be added and used in one transaction, and no value can ever be
dropped. Both are acceptable here: the three triggers are a closed vocabulary the
API has already published.

### 5. The `CHECK` on `marketplaces.name` is defence in depth, and says so

`MarketplaceRegistrationService:32` already rejects a bad name with a proper API
error, so no caller sees new behaviour and the constraint is not a new refusal
path. Its job is that the estate reconciler, a future service method, or a test
fixture cannot introduce a name that breaks the assumption the scope columns were
built on. The comment on the constraint should say that, so a later reader does not
mistake it for the primary validation and move it.

## Risks / Trade-offs

- **A scope list widens through the conversion** → the one outcome that must be
  impossible. The conversion is not a data migration (there are no rows to
  migrate pre-1.0), so the risk is entirely in the new read and write paths;
  covered by the negative tests rather than by inspection.
- **`openapi.json` drifts by accident** — a record type changing from `String` to
  `List<String>` internally can surface in a DTO nobody meant to touch → the
  equality test is the gate, and the task list asserts no regeneration rather than
  accepting one.
- **The extracted helper changes behaviour for `vetters`** → it must be a pure
  move; the existing vetting-chain tests are the check that it was.
- **The asymmetry in decision 3 is forgotten** and `events` keeps a delimited
  payload indefinitely → recorded as PR C scope in the readiness tracker, not only
  here.
- **Every local database is recreated**, since editing `V1` changes its checksum.
  Expected pre-1.0, and stated so nobody reads a failed Flyway validation as a
  defect.

## Migration Plan

None, and that is a property of being pre-1.0 rather than an omission: there is no
deployed database to upgrade, `V1` is edited in place with no `V2__…` behind it,
and a data-repair migration would have no rows to repair. Local and CI databases
are dropped and rebuilt. Rollback is reverting the commit and recreating the
database again.
