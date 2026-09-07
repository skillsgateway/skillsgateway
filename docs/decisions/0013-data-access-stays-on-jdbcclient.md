# ADR 0013 — Data access stays on JdbcClient; JPA is not adopted

*Accepted, 2026-09-08. Supersedes the reasoning of the data-access decision in [ADR 0002](0002-toolchain-and-product-decisions.md) and re-affirms its conclusion on different grounds.*

## Context

ADR 0002 rejected JPA/Hibernate with one sentence:

> supported under native-image but adds build-time enhancement, metadata weight,
> and lazy-proxy edge cases — for a three-table schema it buys nothing.

Both halves of that are now false.

**The native-image premise is void.** [ADR 0012](0012-native-image-as-the-release-artifact.md)
drops the native image in favour of a JVM container, so "supported under
native-image but…" no longer describes the deliverable. The single strongest
argument against a reflective ORM went with it.

**The schema is not three tables.** It is 18, across 15 `JdbcClient`
repositories and roughly 2,600 lines, with 12 native PostgreSQL enum types.

So the decision genuinely had to be re-made rather than defended, and the
question was put deliberately: with native image gone, should this project adopt
JPA? Two independent assessments were taken. Both concluded no, and — this is the
part worth recording — both reached that conclusion for a reason neither started
with.

## The finding that decided it

**Concurrency correctness in this system lives in SQL, in guarded single-statement
updates.** Twenty statements across nine repositories take the form:

```sql
UPDATE snapshots SET state = 'revoked'::snapshot_state, …
 WHERE id = :id AND state = 'approved'::snapshot_state RETURNING *
```

The predicate in the statement is the mechanism: a concurrent revocation, or a
second sweep pass, matches zero rows rather than revoking twice. The same shape
carries token revocation and the webhook dispatcher's lease claim, where a rival
dispatcher's conditional update matches nothing.

This is not incidental. Measured across `src/main/java`:

| | Count |
| --- | ---: |
| Guarded `UPDATE … RETURNING *` statements | 20 |
| `@Transactional` annotations | 3 |
| `@Version` / optimistic locking | 0 |
| `SELECT … FOR UPDATE` | 0 |

There is no other concurrency control. The snapshot state machine — held to
approved or rejected, approved to revoked, revoked re-decidable — *is* these
statements, and `ApprovalService`, a named trust boundary, sits directly on them.

JPA's native idiom is load, mutate, flush at commit, which re-introduces exactly
the read-modify-write race these statements were written to close. JPA has no
first-class `UPDATE … RETURNING *`. So a competent migration keeps all twenty as
native `@Modifying` queries and then defuses the persistence context around them:
stale first-level-cache reads after bulk updates, explicit `clearAutomatically`,
flush ordering where a statement's read-back feeds a subsequent git operation.

**The sharp edges stay SQL either way.** What JPA would take over is the roughly
60% of statements that are plain finders and inserts — the part that was never the
problem.

## Decision

**Data access stays on `JdbcClient` + Flyway on PostgreSQL. JPA/Hibernate is not
adopted**, and native PostgreSQL enum types are retained.

## Why not, stated as costs rather than distaste

- **Entities cannot be records.** Every domain type here is an immutable record,
  several doubling as OpenAPI schema types. JPA means 18 mutable entity classes
  *alongside* the existing records, plus converters. For the CRUD tables the layer
  gets larger, not smaller.
- **The re-verification is not optional.** Changing when a write becomes visible,
  at a trust boundary, is a change this project's own rules class as requiring
  adversarial tests and the evidence-first discipline. That is a campaign, not a
  refactor.
- **A hybrid is worse than either.** Coexistence is technically fine — one
  `DataSource`, one transaction manager — but it leaves two idioms permanently in
  a 2,600-line layer.

## What JPA would genuinely have improved

Recorded because it is real, and because a future reader should not think this was
one-sided. Two true parent-child aggregates — `snapshot_closures` to its members,
and `vetting_runs` to verdicts and findings — are hand-rolled today, including an
N+1 per verdict and a member-insert loop. Cascade persist and fetch joins would
tidy both. Derived finders would remove boilerplate across the plain tables.

Those are worth fixing. They do not require an ORM: batching fixes the loops, and
`DataClassRowMapper` addresses the mapper boilerplate. See the linked issue.

## Does freedom to reshape the schema change this?

**No**, and this was asked explicitly. The deciding factor is not the enum types
or the casts they require; it is that concurrency correctness lives in guarded
statements and the reporting paths are ledger aggregates. No schema reshaping
makes a guarded transition ORM-native without moving the guard into application
code and adopting locking — which is a downgrade at a trust boundary, not a
modernisation.

A schema change was floated during this discussion and is **not** endorsed here:
`CREATE CAST (varchar AS <enum>) WITH INOUT AS IMPLICIT` per enum type, which
would let PostgreSQL cast transparently and remove the explicit `::snapshot_state`
casts. It was proposed on the strength of a cast count that turned out to be
wrong by an order of magnitude — the figure quoted was 189, the real number is
**21**, because the grep had matched Java method references (`::map`, `::name`,
`::new`) rather than SQL casts. Twenty-one casts across 2,600 lines is not a
boilerplate problem.

A narrower argument for it survives — a missing cast fails at runtime rather than
at compile time, so an implicit cast removes an error class rather than a quantity
of noise — but that is a judgement to make on its own merits, not a consequence of
this decision. It is carried in the linked improvement issue, explicitly flagged as
optional.

## What would reopen this

- **A motivation this ADR does not capture.** The proposal to adopt JPA came from
  the maintainer, and the underlying reason was never fully established. If it is
  boilerplate fatigue, the linked improvement issue answers it inside the current
  stack. If it is standardisation, ecosystem familiarity, or an expectation about
  who will maintain this later, that is a legitimate argument neither assessment
  weighed, and this ADR should be superseded rather than cited against it.
- **A domain shift toward object graphs.** If the schema grows aggregates that are
  navigated rather than queried, the balance changes.
- **First-class `UPDATE … RETURNING` support** landing in JPA or Hibernate such
  that the guarded statements are expressible without dropping to native queries.

## Consequences

- ADR 0002's data-access conclusion stands; its recorded reasoning is superseded
  by this document. Issue #312, which proposed amending that sentence in place, is
  absorbed here and can be closed.
- The guarded-update pattern is now documented as load-bearing rather than
  incidental. It should be treated as a deliberate design property: a reviewer who
  sees `WHERE … AND state = …` in an update should understand it is the
  concurrency control, not a defensive habit.
