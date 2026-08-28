## Context

`V1__init.sql` declares every enum-valued column as `TEXT NOT NULL CHECK (col IN (...))`.
Issue #136 lists six such columns; a full sweep of the migration finds **eleven**. The five
the issue's grep missed are `marketplaces.origin`, `marketplaces.push_policy`,
`vetting_runs.outcome`, `vetting_findings.severity` and `role_grants.role`.

The rule separating them from the other `CHECK`s in the file is mechanical: *a `CHECK` that
enumerates a closed value set becomes a type*. `scope_value <> ''`, `principal <> ''`,
`name <> ''`, `expression <> ''`, `justification <> ''`, `approved_by <> ''` and the two
cross-column marketplace constraints assert something else and stay as they are.

One near-miss is deliberately excluded. `vetting_runs.trigger` holds a closed set
(`ingestion`, `revet-scheduled`, `revet-manual`) and is documented as one, but it carries no
`CHECK` today, so the database accepts anything. Converting it would *add* a restriction
rather than preserve one — a behaviour change, and a different decision from this one. It is
worth doing; it is not this change.

## Goals / Non-Goals

**Goals:** every enumerated column carries its set as a type; the refusal the `CHECK`
provided is preserved exactly; the convention is written down where new work will meet it.

**Non-Goals:** no API, portal, configuration or facade change; no new value in any set; no
change to how Java models these values (they stay `String` constants or the existing Java
enums, and the mappers are untouched); no conversion of `vetting_runs.trigger`.

## Decisions

### Convert in place in `V1__init.sql` rather than adding `V2__…`

`.claude/skills/code-conventions` already states the rule — "Single `V1__init.sql` until the
owner says otherwise — fold schema changes into it" — and the preconditions for it still
hold, which is what makes following it safe rather than merely obedient:

- **Nothing has been released.** `git tag -l` is empty, `gh release list` is empty, and the
  Helm chart's `version`/`appVersion` are the hand-pinned `0.1.0` placeholder the release
  workflow is being built to stamp. There is no published baseline whose schema anyone can
  be upgrading from.
- **No deployment can hold data.** The only PostgreSQL any environment has is the
  Testcontainers/Arconia dev service, which is created and dropped per run; local
  development uses the same dev service, wiped on restart.

An `ALTER TABLE ... ALTER COLUMN ... TYPE ... USING` migration would therefore migrate
nothing, while permanently adding a second file whose only purpose is to describe a
transition no database ever made. The alternative was considered specifically because
converting in place is irreversible for anyone holding data; the check above is what
establishes that nobody does.

The `DEFAULT`s (`'upstream'`, `'append-only'`, `'on-demand'`) and the table-level
`marketplaces_hosted_is_on_demand` constraint that reads `sync_mode` survive unchanged: an
unadorned literal is coerced to the column's type, so both keep working verbatim, and a test
asserts the constraint still fires.

### Type names are `<singular table>_<column>`

`state` appears on `snapshots`, `webhook_deliveries` and `vetting_verdicts` with three
different value sets, so the column name alone is not a name. Qualifying by table gives one
mechanical rule with no exceptions and no judgement calls: `snapshot_state`,
`webhook_delivery_state`, `vetting_verdict_state`, `marketplace_sync_mode`, and so on.
A shared `severity` or `role` type across future tables was rejected — it couples two tables'
value sets together forever, and forever is unusually long here (see below).

### Write through an SQL cast, not `PGobject`

Every repository binds named parameters (`.param("state", state)`). Casting in the SQL
(`:state::snapshot_state`) changes only the statement text and leaves every call site,
signature and mapper as it was. A `PGobject` would put a driver type into the parameter
binding of six repositories and turn one-line `.param(...)` calls into constructions.
Spring's named-parameter parser handles `:name::type` — it consumes the parameter name up to
the `:` and then skips `::` as the cast operator — which is what makes the terse form
available at all.

Reads were verified rather than assumed: the driver returns an enum column as a `String`, so
`rs.getString("state")` and `Severity.of(rs.getString("severity"))` are unchanged, and the
round-trip tests read every value back through the existing mappers.

### The value set is now close to permanent

Both verified against PostgreSQL 18.6:

- `ALTER TYPE ... ADD VALUE` followed by a statement *using* that value in the same
  transaction fails (`unsafe use of new value ... / New enum values must be committed before
  they can be used`). Flyway runs a migration in one transaction, so adding a value and
  backfilling rows with it is two migrations.
- `ALTER TYPE ... DROP VALUE` fails with `dropping an enum value is not implemented`.
  Removing one means creating a replacement type, rewriting every dependent column, and
  dropping the old type.

This is the cost of the change, not a footnote to it, so it is recorded in
`.claude/skills/code-conventions` next to the rule itself rather than left to be
rediscovered by whoever first tries to rename a value.

## Risks / Trade-offs

- **A future value change is materially harder than editing a `CHECK`.** → Written into the
  conventions, with both constraints stated, so the cost is visible before the decision.
- **A missed write path would only surface at runtime.** → The gates run every repository
  write path; the round-trip tests exercise every value of every converted type through the
  repository that owns it, and `./mvnw verify` covers the rest of the suite.
- **A cast written against the wrong type name is a runtime error, not a compile error.** →
  The type-name test asserts the declared type of all eleven columns against the expected
  name, so a rename that misses a cast fails the build.

## Migration Plan

None beyond the schema file itself: there is no database in existence to migrate. A rollback
is a revert of the commit; the next run builds the previous schema from scratch.
