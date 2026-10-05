# Design: ingest-as-a-job

## Context

Motivation: see proposal.md. Current state:

- `AdminController.ingest` calls `IngestionService.ingest(marketplace, actor)`
  in the request, then writes `snapshot-ingested` to the ledger and emits
  `marketplace.snapshot.ingested`. An `IngestionException` becomes a `502`
  problem with `reason`, `rootCause` and `nextStep` (GW_INGEST_0038 — An
  upstream failure states its cause and a next step).
- `IngestionService.ingest` serializes per marketplace with an in-JVM
  `ReentrantLock` and records every attempt's end on the marketplace row
  (`last_ingest_at/outcome/reason`, GW_INGEST_0039 — The last ingest attempt is
  recorded on the marketplace). Every trigger goes through it: on demand,
  the sync sweep, the inbound webhook (already asynchronous, its own
  single-thread executor), and a hosted push.
- The gateway runs scaled out on the object-store backend, so a poll may land
  on a replica other than the one doing the work.

## Goals / Non-Goals

**Goals:**

- The on-demand request returns at once, so a proxy timeout cannot report an
  ingest that worked as one that failed.
- Anyone reading the marketplace can see what an ingest is doing right now and
  how long it has been at it, whichever replica they reach.
- What the `502` problem told the caller survives into the recorded outcome.

**Non-Goals:**

- **A job table or job history.** One running ingest and the last finished one
  per marketplace is all the issue asks for. The ledger already holds the
  history (GW_AUDIT_0010).
- **Percent-complete.** The stages are the progress. Nothing inside a stage can
  estimate its own length (a fetch does not know the pack size up front).
- **Cancelling a running ingest.**
- **Changing the webhook trigger's executor or the sync sweep.** They gain
  stage reporting because it lives in `IngestionService`, nothing else.

## Decisions

### D1. Running state on the marketplace row, keyed by an attempt id

Four columns on `marketplaces`, all null when nothing runs (a CHECK keeps them
whole):

```
ingest_attempt       UUID
ingest_stage         marketplace_ingest_stage  -- queued | fetching | evaluating-manifest | vetting
ingest_started_at    TIMESTAMPTZ
ingest_heartbeat_at  TIMESTAMPTZ
```

plus two on the finished record:

```
last_ingest_snapshot_id  BIGINT REFERENCES snapshots ON DELETE SET NULL
last_ingest_failure      JSONB   -- {reason, rootCause, nextStep}; present iff outcome = failed
```

`last_ingest_reason` stays: it is the readable sentence (`describe()`), and the
JSONB holds its parts for a client to act on, as the problem properties did.

Every write that reports progress (stage change, heartbeat, clear) is
conditional on `ingest_attempt = :mine`, so an attempt never overwrites or
clears another's progress. An attempt *starts* (on acquiring the per-marketplace
lock) by writing its own id unconditionally: it is the one doing the work now.

*Rejected:* an in-memory registry. A poll reaching another replica would see
nothing. *Rejected:* a separate `ingest_jobs` table. That is an object type with
a lifecycle and a retention question, for a single row per marketplace.

### D2. Claim, then queue

`POST …/ingest`:

```sql
UPDATE marketplaces
   SET ingest_attempt = :attempt, ingest_stage = 'queued',
       ingest_started_at = now(), ingest_heartbeat_at = now()
 WHERE id = :id
   AND (ingest_attempt IS NULL OR ingest_heartbeat_at < now() - :stale)
```

One row updated: the attempt is claimed and submitted to the job executor, and
the response is `202` with the status, so the first poll already sees `queued`.
No row updated: a live ingest exists (any trigger, any replica), and the
response is `202` with *its* status. Clicking twice, or ingesting while the sweep
runs, does not queue duplicate work. The automated triggers do not claim, as
today. A webhook arriving mid-ingest may carry a newer commit, so it still waits
on the lock and runs after.

### D3. Heartbeat and interruption

A replica that dies mid-ingest leaves its row saying `vetting` forever, and D2
would then refuse every later ingest. So each replica keeps the attempts it owns
(claimed or running) in a set. A single-thread timer of its own, every 10 s, renews
`ingest_heartbeat_at = now()` for exactly those ids. A status read treats an
attempt whose heartbeat is older than 60 s as **interrupted**
(GW_INGEST_0067 — An ingest whose replica stopped is reported as interrupted),
and D2's claim takes it over. Both times come from the database's `now()`, so
replica clock skew does not matter. The intervals are constants, not
configuration: they bound a display, and no operator has a reason to tune them.

This is not an estate sweep. It touches only its own replica's rows and needs
no lease (GW_FACADE_0030 — A scheduled background pass runs on one replica at a time). It is
deliberately not `@Scheduled`, the mark `SweepLeaseDisciplineTests` reserves for leased sweeps.

### D4. Stages

`IngestionService` reports through the attempt: `fetching` when the lock is
taken, `evaluating-manifest` before `serve` (which includes resolving admitted
external sources), `vetting` before facts and the chain, the latter only for a
held snapshot. The outcome's final state comes from the snapshot: held or
rejected. On finish, `recordIngest` writes the finished record unconditionally
and clears the running columns only where the attempt is still its own.

### D5. API

- `POST /api/v1/marketplaces/{name}/ingest` → `202`, `Location:
  /api/v1/marketplaces/{name}/ingest`, body `IngestStatus`. Still
  approver-gated, and `404` for an unknown marketplace. It no longer answers
  `201` or `502`.
- `GET /api/v1/marketplaces/{name}/ingest` → `200 IngestStatus`, readable by
  anyone who can list marketplaces. Added to the `marketplaces:ingest` machine
  scope beside the POST it follows.

```
IngestStatus {
  marketplace: string
  running: { stage, startedAt, interrupted: boolean } | null
  last:    { at, outcome: succeeded|failed, snapshotId?, snapshotState?,
             reason?, failure?: { reason, rootCause, nextStep } } | null
}
```

`running` and `last` are separate because both are true at once: an ingest is
running, and the previous one ended. `MarketplaceView` gains
`lastIngestSnapshotId` (additive).

The `201` → `202` change is breaking. Pre-1.0 it costs nothing but the
declaration: `!` in the PR title and the `⚠️ BREAKING CONTRACT` label.

### D6. The job

`ingestion/IngestJobs` owns the claim, a fixed pool of **two** daemon threads,
and the run: `ingestionService.ingest(marketplace, actor, attempt)`, then the
`snapshot-ingested` ledger entry and the webhook emit, both moved out of the
controller unchanged. A failure is already recorded by `IngestionService`, and
the job only logs it. Two threads let one slow first ingest leave room for
another marketplace. Further work waits visibly as `queued` rather than
competing for memory. On shutdown the pool is stopped. Claimed attempts stop
heartbeating and read as interrupted, which is the truth.

### D7. Portal

`useIngest` returns the status. `useIngestStatus(name)` polls the GET every
second while `running` is non-null and stops when it clears, then invalidates the
marketplace and snapshot queries. The marketplace page shows the stages as a row
of steps: Queued → Fetching → Evaluating manifest → Vetting → outcome. The
current step carries the elapsed time, ticking client-side from `startedAt`.
Afterwards it shows the outcome (held, rejected, or failed with the reason) beside the existing
last-ingest line. A page opened while a scheduled ingest runs shows it too,
because it reads the status on load.

## Risks / Trade-offs

- **Two replicas ingesting one marketplace at once** (a sweep on one, a click on
  another past a stale bound) report whichever attempt started last. This is
  no worse than today, where the same race exists and only the unique constraint
  arbitrates.
- **A heartbeat write every 10 s per running ingest.** That is negligible
  beside the ingest itself, and nothing is written when nothing runs.
- **Clients that read the snapshot from the POST response break.** That is the
  declared break. The portal, the e2e suite and the docs move in this PR.
