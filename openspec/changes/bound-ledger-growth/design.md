# Design: bound-ledger-growth

## Context

See `proposal.md — Why`. The design-level facts that constrain the approach:

- `fetch_log` (`V1__init.sql:201`) is `BIGSERIAL` PK plus two indexes:
  `idx_fetch_log_adoption`, partial on `event = 'upload-pack'`, and
  `idx_fetch_log_actor_type`. No foreign keys, denormalised on purpose, and it
  must outlive the token rows it names.
- `audit_sinks.cursor_position` is "id of the last `fetch_log` entry handed to
  this sink; the only per-consumer state" (`V1__init.sql:347`). It is a
  `fetch_log.id`, so the lowest cursor across enabled sinks is directly a
  watermark in the ledger's own key space.
- `RetentionScheduler.compact()` runs six-hourly under `COMPACT_LEASE`, with the
  lease deliberately set to twice the interval because it runs `git gc`. It is
  gated by `skills-gateway.retention.enabled`, default `false`.
- `RetentionService` caps each pass at `properties.batchSize()` — **200**
  (`RetentionService:145,235`), one batch per pass, no loop.
- Facade entries are written by `FetchAuditHook.record`, called from
  `GitFacadeConfiguration` for `info-refs` and `upload-pack` and from
  `HostedPushHook` for the three publication events. Administrative entries come
  from `AdminAuditLogger` with `source = "admin"`.

## Goals / Non-Goals

**Goals.** An operator who exports the ledger can bound it. Every operator can
see how large it is and how far behind export is. Nothing in the eligible set is
evidence that no consumer has taken.

**Non-Goals.** Bounding the ledger of a deployment that does not export — see
Decision 3, where that is a deliberate refusal rather than a gap. Anonymising or
redacting a retained entry; a GDPR erasure request against `principal` is a
separate problem this change does not claim. Touching F7.2 (the nine-argument
`append()`, the double-duty `source`).

## Decisions

### 1. The trim rides the compaction pass. It is not a third sweep.

The stop rule makes a new `@Scheduled` component a cost to argue, and here there
is nothing to argue for: `compact()` is already the pass that hard-deletes, is
already leased across replicas, and is already behind the switch that says this
deployment has asked the gateway to delete things. A ledger trim is the same act
on a different table.

It goes in `compact()` and not `evaluate()` because `evaluate()` only marks
eligibility and the hourly cadence buys nothing — the trim's cutoff is measured
in days.

*Alternative considered:* its own `@Scheduled` with its own lease key, so a long
`git gc` cannot starve the trim. Rejected: `COMPACT_LEASE` already holds for
twice the interval precisely to tolerate a long `gc`, and the trim is bounded by
its own time budget (Decision 4), so it cannot be the starving party either. A
third sweep would buy independence nothing needs and cost a config leaf, a lease
key and a component.

### 2. Eligibility is an allowlist of the two read events, not a denylist of `admin`

```
event IN ('info-refs', 'upload-pack')
  AND ts < :cutoff
  AND id <= :watermark          -- min(cursor_position) over enabled sinks
```

Three things about this shape are deliberate:

**It is written on `event`, not `source`.** The assessment describes `fetch_log`
as "split by a `source` column", but `source` holds
`request.getRemoteAddr()` on a facade entry (`GitFacadeConfiguration:116`) and
the literal `admin` only on an administrative one. `source <> 'admin'` happens to
be correct today — an IP address is never the string `admin` — and is exactly the
kind of implicit coupling that breaks quietly later.

**It is an allowlist.** A ledger event kind added after this change is not
trimmable until someone adds it to the set on purpose. The failure direction of a
forgotten update is then "the table grew", not "we deleted evidence nobody meant
to delete".

**The publication events stay out.** `HostedPushHook`'s three events are facade
entries by code path but publication records by meaning, and they are low-volume,
so exempting them costs nothing and keeps the trimmable set to exactly the
volume problem: the two read events.

### 3. No enabled sink means nothing is eligible, and the docs say so

With no enabled sink the minimum cursor is undefined. Two readings are available
and only one is defensible: either "no consumer to protect, delete by age alone",
or "nothing has been exported, so the ledger is the only copy and retention does
not touch it". The second follows the principle `GW_RETENTION_0008 — An
administrative revocation is never erased by retention` already establishes, and
the first would make the default deployment — retention off, no sinks, which is
every fresh install — the one where the gateway silently destroys its own audit
trail the day someone enables retention.

So the trim is a no-op without a sink, and the consequence is written down rather
than discovered: **a deployment that does not export keeps its ledger forever.**
The gateway cannot be both the sole custodian of audit evidence and the thing
that deletes it. The gauge in Decision 5 is what keeps that honest.

*Alternative considered:* a manual watermark leaf for a deployment that pulls the
NDJSON export (`GW_AUDIT_0003 — Audit ledger streaming export`) instead of
registering a sink. Rejected: it is a second config leaf whose only job is to let
an operator assert something the gateway cannot verify, and getting it wrong
deletes evidence. The documented path to bounding the table is to register a
sink.

### 4. The pass loops to a time budget; it does not reuse `batchSize`

`batchSize` is 200 per pass. At the proposal's ~190,000 rows a day, 200 rows
every six hours is three orders of magnitude below the insert rate — a trim that
cannot keep up, while its log line says it deleted something. Snapshots can use
one small batch because there are few of them; ledger rows cannot.

So: delete in chunks by ascending `id` (which is the primary key, so each chunk
is an index range), each chunk its own transaction, looping until a chunk comes
back empty or a wall-clock budget expires. Chunk size and budget are
**constants**, not `skills-gateway.*` leaves — they bound work rather than
express policy, which is the line `ConfigSurfaceBudgetTests` exists to hold.

Chunking rather than one statement also keeps the WAL burst and the lock
footprint proportionate: the first pass after an operator enables this on a
mature deployment may face tens of millions of rows, and a single `DELETE` of
that is a long transaction on the database the serving path writes to.

Being unable to finish in one pass is fine and expected. The pass is idempotent
and there is another in six hours.

### 5. Two gauges, on `GW_OBSERVABILITY_0003`'s existing terms

Ledger depth, and the age of the oldest entry below no enabled sink's cursor —
the second being the number that predicts trouble in the Decision 3 deployment,
where the first will simply rise forever.

They join `GW_OBSERVABILITY_0003 — Always-recorded gateway metrics and
observations` rather than arriving as a new requirement, because that requirement
enumerates the instruments the gateway always records and holds the "recorded
always, exported opt-in" property for all of them. A fifth observability
requirement would split that property across two statements that can drift.

A depth gauge should not be a `SELECT count(*)` over tens of millions of rows
every scrape. Use the planner's estimate (`pg_class.reltuples` for the relation),
which is what a gauge of this kind wants: cheap, and accurate enough to see a
trend line bend.

### 6. Native range partitioning is rejected

The assessment recommends "a partitioning strategy". It does not earn itself here,
for four reasons, in descending order of weight:

1. **Partition creation is a recurring obligation, and the only place to put it is
   the thing we are avoiding.** Pre-creating a fixed set in `V1__init.sql` fails
   silently when it runs out; a `DEFAULT` partition catches the overflow and then
   grows unbounded and cannot be dropped, and attaching a real partition later
   means moving rows out of `DEFAULT` under a lock. Creating them on a schedule
   means a sweep — and it could not be the retention pass, which **defaults to
   off**, so a deployment that never enables retention would eventually take an
   `INSERT` failure on the facade's serving hot path. Trading unbounded disk for a
   serving outage is a strictly worse deal.
2. **It regresses a live read.** `idx_fetch_log_adoption` becomes one index per
   partition, so the staleness report of `GW_OBSERVABILITY_0002 — Staleness
   reporting against the served tip` — "the latest content-transferring fetch per
   `(principal, marketplace)`" — goes from one index probe to a probe per
   partition.
3. **It changes the primary key.** A unique constraint on a partitioned table must
   contain the partition key, so the PK becomes composite over `(id, ts)`. The
   cursor contract survives (it is `id`-ordered and `id` stays globally unique
   from the sequence), but a schema change is no longer "none".
4. **It solves a problem this change does not have.** Partitioning's win is
   `DROP TABLE`-cheap removal with no bloat. The trim's cost is chunked deletes
   that autovacuum reclaims — real, but paid on a 6-hourly background pass on the
   table nothing but this pass and the append path touch.

If a deployment ever outgrows the chunked delete, partitioning remains available
and the argument above is the record of what it would have to answer. The
re-examination trigger is concrete: the depth gauge from Decision 5 climbing while
the trim reports a full time budget every pass.

### 7. Stopping is a live option, and this is what defeats it

Parking this was on the table. What defeated it: the gateway offers **no**
mechanism today — an operator with a retention obligation cannot meet it at any
price short of hand-written SQL against an append-only table, and a compliance
feature you have to break the invariant to use is not a feature. Decision 3's
refusal is itself part of the answer, so what ships is a mechanism plus an honest
statement of when it does nothing.

*Alternative considered and rejected: stop recording `info-refs` at all.* It would
remove roughly 95% of the growth by deletion rather than addition, which is what
the stop rule prefers, and no production query reads those rows —
`FetchLogRepository:125` and the partial index both exclude them, and
`GW_OBSERVABILITY_0002` says "content-transferring". It is rejected because the
replacement, the `skills_gateway.facade.fetches` counter, is deliberately tagged
by event only and never by principal or marketplace
(`FetchAuditHook:45`), so a credential that enumerates marketplaces without ever
fetching would stop being visible per-principal. That is a detection loss on the
facade trust boundary bought with disk, and it would also need
`GW_AUDIT_0001 — Fetch audit log` reworded away from "every facade fetch".
Recorded here so it is not rediscovered as a fresh idea.

## Risks / Trade-offs

- **A lagging or wedged sink silently pins the watermark, and the table keeps
  growing** → this is the correct behaviour, not a bug, but it must be visible:
  it is exactly what the oldest-un-exported gauge measures, and the trim's log
  line should name the sink holding the watermark.
- **A sink's cursor is reset backwards for replay (`GW_AUDIT_0005`) past the
  trimmed boundary** → replay yields the oldest surviving entry onward, silently
  fewer rows than asked for. Documented in the export guide; the spec delta
  records the bound.
- **A newly registered sink starts at `0` and never receives trimmed history** →
  inherent to trimming at all. Register sinks before enabling the trim; the guide
  says so.
- **First pass on a mature deployment is a large delete** → chunked, budgeted,
  resumable across passes; no single long transaction.
- **Autovacuum falls behind the delete rate and the table bloats** → the delete
  rate cannot exceed the append rate over time, since the trim only removes what
  was appended; the risk is the one-off catch-up, which the time budget spreads.
- **An operator sets `ledger-max-age` intending to bound the table, has no sink,
  and believes it worked** → the trim logs that it removed nothing and why, and
  the gauge does not move. The docs state the precondition in the same paragraph
  as the setting.

## Migration Plan

Nothing to migrate. No schema change, and the trim is off in every existing
deployment: it requires both `retention.enabled` (already opt-in) and a
`ledger-max-age` that no existing configuration sets. Rollback is reverting the
code or unsetting the leaf — but note that rollback does not restore trimmed rows,
which is why the eligibility predicate, not the rollback path, is where the
assurance has to live.

## Requirement text to mint

Drafted here for `tasks.md` step 1 to write into `docs/reqstool/requirements.yml`.

**`GW_RETENTION_0009` — Audit ledger read entries are trimmed only behind every
sink's export position**

> The system shall, while retention is enabled and a ledger maximum age is
> configured, remove during its scheduled hard-delete compaction pass those
> append-only audit ledger entries that record a facade read — a ref
> advertisement or a pack send — whose timestamp is older than that maximum age
> and whose ledger position is at or below the lowest export position held by any
> enabled audit export sink, shall remove nothing while no enabled sink exists or
> no maximum age is configured, shall bound each pass by a fixed work budget and
> resume on the next pass rather than exceed it, and shall report per pass how
> many entries it removed and which sink holds the position that bounded it.

> *Rationale*: nothing else bounds the one table that grows with every client
> poll, and an operator under a retention obligation has no mechanism at all
> today; the export position is the only evidence the gateway has that some other
> system now holds the entry, so trimming behind it is the one deletion that
> destroys no record, and a deployment that exports nothing is deliberately left
> unbounded rather than silently stripped of its only copy.

**`GW_RETENTION_0010` — Administrative and publication ledger entries are never
trimmed**

> The system shall never remove an append-only audit ledger entry recording an
> administrative action or a first-party publication, whatever its age and
> whatever the export positions permit, admitting to trimming only a closed set
> of facade read events so that a ledger event kind introduced later is not
> eligible until it is deliberately admitted.

> *Rationale*: the compliance-bearing half of the ledger is also its low-volume
> half, so exempting it costs nothing and removes the whole class of error where
> a retention setting quietly erases the record of who decided what — the
> principle GW_RETENTION_0008 already fixes for revocation, generalised; a closed
> admitted set makes a forgotten update grow the table rather than delete
> evidence.

Both need an SVC. `GW_AUDIT_0005` and `GW_OBSERVABILITY_0003` are modified in
place — see their spec deltas — and their existing SVCs stand.
