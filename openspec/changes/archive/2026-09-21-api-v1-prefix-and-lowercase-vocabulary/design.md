# Design: api-v1-prefix-and-lowercase-vocabulary

## Context

See `proposal.md — Why`. Three constraints shaped the work:

- The security chains match `/api/**` and `/status/**` as *families*, not as
  concrete paths (`SecurityConfig:232,316` and `:141`).
- Four enums already carried a `stored()` returning the lower-case name for their
  PostgreSQL column; seven `@Schema(allowableValues = …)` arrays were hand-written
  uppercase strings, independent of any enum.
- `RoleBootstrapGuard` took `RemovedPropertyGuard` as a constructor parameter purely
  for startup ordering — "a stale manifest has to be reported before an estate nobody
  can administer".

## Decisions

### 1. The security matchers stay at the family, not the version

Narrowing `securityMatcher` to `/api/v1/**` would leave `/api/anything-else` falling
through to the catch-all chain rather than being refused by the API chain. Nothing
maps there today, so it would 404 — but the difference between "no route" and "not
gated" is the kind of thing that stops being true when someone adds a route. Matching
the family costs nothing and cannot rot.

### 2. The wire form *is* the storage form

Where a lower-case `stored()` already existed for the column, `@JsonValue` was put on
that method rather than adding a parallel `wire()`. A published enum value that
spells itself differently from the column it came out of is two vocabularies to keep
in step, and the ledger, the API and the database now agree by construction.

The four enums with no column got `wire()` — same shape, named for what it is.

*Alternative considered:* `@JsonProperty` per constant, or a Jackson naming strategy.
Rejected: per-constant annotations are eleven places to forget, and a global strategy
would also rename values that are deliberately not lower-case elsewhere.

### 3. `allowableValues` was wrong about the code, so the code changed

Seven `@Schema(allowableValues = …)` arrays documented uppercase values. The
temptation was to lowercase the strings and move on — but the webhook `VettingSummary`
was populated from `effect.outcome().name()`, so those arrays were *accurate* and
lowercasing them alone would have put a lie in the contract. `VettingService` now
emits `stored()`, and the arrays follow.

`AdminController:702` also built a runtime `choices` list of `PREVIOUS_APPROVED` /
`NOTHING` inside a problem document; that is a wire value too and was corrected with
the rest.

### 4. The removed-property mechanism is deleted, not emptied

Keeping `RemovedProperties.ALL = Map.of()` would leave a guard that reads an empty map
and a policy with no subject — the "kept for later" shape the conventions warn about.
Deleting it drops `RoleBootstrapGuard`'s ordering dependency too, which existed only
to sequence two messages one of which no longer exists.

What is kept is the **argument**, in `compatibility.md`: a property whose removal
reverses an operator's stated intention needs a refusal rather than silence. The next
removal gets its own requirement and its own guard; it does not inherit this one's
corpse.

*This is the one judgment call in the change worth disagreeing with.* The finding
asked for two entries and a doc paragraph; retiring `GW_AUTH_0027` follows from it,
but it is a larger act, and reversing it means keeping a requirement whose property
no supported version reads.

## Risks / Trade-offs

- **A client pinned to an unversioned path breaks** → intended, and free at 1.0.0;
  no deployment exists to break.
- **A lower-cased value reaches a consumer that switched on the old spelling** → the
  same trade, and the reason the change is declared breaking rather than slipped in.
- **`@JsonValue` also governs deserialization**, so request enums (`serveAfter`, the
  waiver `scope`) now accept only the lower-case spelling. Covered by the existing
  request tests, which were updated to send it.
- **A blanket case sweep touches a display string or a normalisation direction** →
  it did: `audit-status.ts` lower-cased its `switch` labels while still calling
  `.toUpperCase()` on the input. The UI test caught it, and the fix was to normalise
  the way the module's own comment already said the server writes.

## Migration Plan

None required of anyone: no deployment, no stored data, and every path and value
changes in one release. Local databases are unaffected — this change touches no
schema.
