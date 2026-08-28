## Why

Every enum-valued column in the schema is declared `TEXT ... CHECK (col IN (...))`. A
`CHECK` enumerates values; it does not create a type. The column is `text` to everything
that introspects the schema, the permitted set is invisible to anything that reads types
rather than constraint expressions, and a typo in a query is a runtime constraint
violation rather than a type error. PostgreSQL has the right construct — `CREATE TYPE ...
AS ENUM` — and the project has decided to use it for new work (issue #136); the eleven
columns already in `V1__init.sql` should not be the exception.

## What Changes

- Eleven enum-valued columns become native PostgreSQL enum types. The rule applied is
  mechanical: *a column whose `CHECK` enumerates a closed value set becomes a type*.
  `CHECK`s that assert something else (`scope_value <> ''`, the cross-column
  `marketplaces_upstream_has_url` and `marketplaces_hosted_is_on_demand`) are untouched.
- Type names are `<singular table>_<column>`, because three different tables carry a
  column called `state` with three different value sets and a bare `state` type would
  collide.
- The repositories cast on write (`:param::marketplace_sync_mode`). Reads are unchanged:
  the PostgreSQL driver hands an enum column back as a `String`, which is what every
  `rs.getString(...)` mapper already expects.
- The convention is written into `.claude/skills/code-conventions`, together with the two
  PostgreSQL facts that make it a trade rather than a free win: a new enum value cannot be
  *used* in the transaction that adds it, and a value can never be dropped.
- No API, portal, configuration or facade behaviour changes. What a caller may send and
  what the gateway answers are byte-identical before and after.

## Capabilities

### New Capabilities

- `persistence-schema`: how the schema represents a closed set of values.
  - **GW_0125** (new) — enumerated persisted values are database types: the permitted set is
    readable from the column's declared type, and the database refuses anything outside it.

### Modified Capabilities

None.

The honest reading is that this is a refactor of *how* the existing requirements are
satisfied, not of *what* they require: what a caller may send and what the gateway answers
are byte-identical. What GW_0125 states is not new behaviour but the invariant the `CHECK`
constraints were quietly providing — write it as a requirement or the guarantee lives only in
eleven constraint expressions that no requirement names, and the tests proving it belong to
nothing. Requiring it also gives the *next* enumerated column somewhere to be wrong against.

The requirement is deliberately about the guarantee (a value outside the set is refused, and
the set is discoverable) rather than about PostgreSQL enum types, so it does not put a storage
mechanism into the requirements SSOT. The mechanism is a decision, and it lives in
`design.md` and in `.claude/skills/code-conventions`.

## Impact

**Schema** — `src/main/resources/db/migration/V1__init.sql`: eleven `CREATE TYPE` statements
and the eleven columns that use them.

**Java** — write-side casts in `MarketplaceRepository`, `SnapshotRepository`,
`AuditSinkRepository`, `VettingRepository`, `WaiverRepository` and `RoleGrantRepository`.
No signature, no record, and no mapper changes.

**Tests** — a new `NativeEnumColumnTests`: per type, the column's declared type, a
round trip through the owning repository, and a refusal of a value outside the set.

**Conventions** — `.claude/skills/code-conventions`.

**Not affected** — the REST API (and therefore `openapi.json`), the portal, the git
facade, every trust boundary, and the documentation site: no page describes the physical
schema.
