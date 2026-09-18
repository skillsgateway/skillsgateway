# Design: single-init-migration

## Context

See `proposal.md` — Why. The constraint that shapes everything below is that
the squash must be provably behaviour-preserving: a database built from the new
single `V1__init.sql` has to be the same database Flyway would have produced by
running the six-file chain. Nothing in the application may observe which route
was taken.

That is not automatic. Three mechanisms in the chain produce results that a
naively rewritten `V1` would get subtly wrong:

- `ALTER TABLE … ADD COLUMN` **appends**, so `access_tokens.last_used_at` and
  `fetch_log.credential_kind` are the last columns of their tables.
- `ALTER TYPE … ADD VALUE` **appends** to the enum's value order, so
  `not_reached` sorts after `disabled` in `vetting_verdict_state`.
- `ALTER … RENAME` preserves the *object* while changing its name, so
  `vetter_toggles`' primary key, check constraints, foreign key, index and
  sequence carry names derived from the old `connector_toggles` spelling only
  where the rename script said so — and `V2` renamed every one of them
  explicitly, so the end state has fully `vetter_`-prefixed names.

Each is a place where "obviously equivalent" and "actually equivalent" differ.

## Goals / Non-Goals

**Goals:**

- One `V1__init.sql` that produces exactly the schema the six-file chain
  produces — same columns in the same order, same enum value order, same
  constraint, index and sequence names.
- A verification that demonstrates that equality rather than asserting it.
- The convention stated where the next contributor will meet it.

**Non-Goals:**

- Any change to what the application does. No table gains or loses a column, no
  constraint is tightened or relaxed, no default changes.
- Upgrading an existing database. There is no upgrade path and deliberately so;
  existing databases are recreated.
- Deciding what happens at 1.0.0. When compatibility starts mattering, the
  convention changes back and `V1` is frozen. That is a later change.

## Decisions

### Verify by diffing two real schemas, not by reading the SQL

The claim "the squashed file produces the same schema" is checkable mechanically
and should be, because reading two thousand lines of DDL for equivalence is
exactly the kind of review this project distrusts.

The procedure, run once during implementation and pasted into `evidence.md`:

1. From `main`, migrate a scratch database with the six-file chain, and
   `pg_dump --schema-only --no-owner --no-privileges` it.
2. From the branch, migrate a second scratch database with the squashed `V1`,
   and dump it the same way.
3. `diff` the two dumps. **The diff must be empty.**

An empty diff settles column order, enum order, constraint names, index names,
sequence names and defaults in one step, and it is evidence a reviewer can
re-run rather than a claim they have to trust.

*Alternative rejected:* asserting equivalence from a careful reading of the
files. That is what produces the column-order and enum-order mistakes this
section exists to catch, and it leaves no artefact behind.

*Alternative rejected:* a Java test that introspects `information_schema` and
compares. It would need the old chain to stay in the tree to have something to
compare against, which is the thing being deleted. The comparison is a one-time
migration concern, not a standing invariant, so it belongs in the change's
evidence rather than in the suite.

### Preserve append position rather than tidying columns into place

`last_used_at` stays the last column of `access_tokens`, and `credential_kind`
the last of `fetch_log`, even though a from-scratch author would have grouped
them with related columns. Likewise `not_reached` is written last in the
`vetting_verdict_state` literal, after `disabled`.

Tidying would be a gratuitous difference that makes the verification diff
non-empty and so destroys the evidence that nothing else changed. The point of
this change is that it is invisible; a cosmetic reordering smuggled inside it
would make that false.

### Keep the comments that explain a column; drop the ones that explain a migration

The later migrations carry substantial commentary, and it divides cleanly:

- **Kept**, rewritten into the declaration it belongs to — why
  `fetch_log.credential_kind` is nullable with no default and not a foreign key;
  why `access_token_last_used` is deliberately unindexed and approximate to a
  one-minute bound; why chain mode and chain order are two tables rather than one
  row; why `vetting_chain_orders.vetters` is an array rather than a join table;
  what `not_reached` means and how it differs from `disabled`.
- **Dropped** — the reasoning about backfilling existing rows, about what a
  migration must not assume of a deployed database, and about why a rename was
  chosen over an additive column. These explain a transition that will no longer
  have happened. A reader of the squashed file has never seen the old name.

The `vetter` naming rationale is the one judgement call: it explains a *name*, so
by the rule above it stays — but compressed to a sentence at the `vetter_toggles`
declaration rather than the migration's full argument, because the collision it
resolved is now only history. It is recorded in `docs/manual/concepts/glossary.md`
already.

### State the convention in `CLAUDE.md`, not only in a doc page

The failure this change fixes is that the convention lived in prose two pages
away from anyone adding a migration, so five people added migrations. The
convention goes where an agent or contributor is looking when they are about to
add one — `CLAUDE.md` alongside the other project rules — and the
`corpus-aware-vetting` change's planned `V2__snapshot_facts.sql` is corrected in
the same PR, because an in-flight proposal contradicting the new rule is how the
rule gets lost again.

### The requirement narrows; the test narrows with it, and says why

`SVC_GW_WEBHOOK_0008` loses only the block that executes the migration file.
The three assertions it also makes — namespaced vocabulary, legacy spelling
refused, namespaced name in header and body — stay untouched, and the test keeps
its `@SVCs` annotation.

To keep the narrowing honest rather than convenient, the deleted block is
replaced by a comment at the test naming the decision that removed it, so a
future reader finds the reason rather than a gap. `docs/reqstool/requirements.yml`
carries the authoritative narrowed text and a revision bump.

## Risks / Trade-offs

- **The squashed file silently differs from the chain** → the empty-diff
  verification above; the change is not complete until that diff is empty and
  pasted into `evidence.md`.
- **A developer with an existing local database gets an opaque Flyway checksum
  failure** → the failure is loud and the cause is named in the PR body and in
  the local-development guide; the fix is to drop and recreate, which costs
  nothing because the data is disposable. The Arconia dev service recreates its
  container database anyway.
- **The convention is wrong after 1.0.0 and someone follows it into a released
  version** → the rule is stated with its condition attached ("while pre-1.0"),
  not as a bare instruction, so the condition is visible at the point of use.
- **`corpus-aware-vetting` is rebased later and reintroduces `V2`** → its design
  and tasks are corrected in this PR rather than left to be noticed.
- **Something outside the repo pins the migration count** — the live runner's
  out-of-order flag is the known case and is handled; a second one would surface
  as a failed start rather than as wrong behaviour.

## Migration Plan

There is no migration, and that is the point. Deployment is: recreate the
database. Concretely, for the one running instance —
`~/agents/skillsgateway/live/run-latest.sh` drops its Flyway out-of-order flag,
and its database volume is removed once so the fresh `V1` applies to an empty
schema.

**Rollback** is `git revert`; any database created from the squashed `V1` is then
discarded the same way. Because no deployment holds data, rollback has no data
cost — which is the whole reason this change is cheap now and would not be after
1.0.0.
