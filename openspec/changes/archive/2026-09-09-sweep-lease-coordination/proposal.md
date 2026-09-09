# Proposal: sweep-lease-coordination

## Why

Eight `@Scheduled` methods run on every replica, and nothing tells a second
replica that a first one is already running the same pass. That is not a
hypothetical: the chart *knows*, and refuses to render more than one replica
while any of five named switches is still on — `GW_FACADE_0014 — Deployment
refuses a storage and replication shape the gateway cannot honour`. So the
object-store backend, which exists to make horizontal scaling possible, cannot
actually be scaled out with the gateway's own defaults.

The gate is also wrong in a smaller way that says something about the larger
one: **it lists five flags for eight sweeps.** `vetting.waiver-sweep-interval`
has no flag at all (deliberately — the pass has no authority over the gate),
`retention` covers two distinct passes, and `mirror.sweep-interval` is not
mentioned. An operator who set all five to `false` and scaled out would still
get N waiver sweeps and N mirror sweeps on every replica. The gate promises a
safety it does not deliver, which is worse than refusing outright.

Two of the sweeps also carry defects that survive being run twice *in the same
process* — they are not merely uncoordinated, they are individually racy:

- `WaiverService.sweepExpired` writes the `waiver-expired` ledger entry **before**
  the conditional `markExpiryRecorded` stamp. Two passes over the same waiver
  each write a ledger entry and only one stamps it, so the ledger — the artefact
  whose whole value is that it is trustworthy — gains a duplicate announcement of
  a single lapse.
- `AuditSinkRepository.updateCursor` is an unconditional `UPDATE`. It is used in
  both directions: the export pass advances it, and `GW_AUDIT_0005` replay rewinds
  it. An export pass that lands between an operator's rewind and the next export
  **silently cancels the replay** by writing the cursor forward again, and the
  operator sees a replay that reported success and delivered nothing.

## What Changes

### A lease table, not an advisory lock

`sweep_leases (name, leased_until, holder, updated_at)` with `name` as the
primary key, and `SweepLeases.runIfLeader(name, lease, body)` around a single
conditional upsert of exactly the shape `WebhookDeliveryRepository.claim()`
already uses.

**`pg_try_advisory_lock` was considered and rejected.** Advisory locks are
*session*-scoped, and under HikariCP a connection returns to the pool still
holding one. A `finally`-unlock cannot guarantee it runs on the same physical
connection that took the lock, so a leaked lock disables that sweep **silently,
for up to `maxLifetime`** — 30 minutes by default, and a disabled re-vetting
sweep is a security control that stopped running without saying so. The codebase
also has almost no transaction management to lean on (five `@Transactional` in
all of `src/main`, no `TransactionTemplate` bean), so the "hold it for the
transaction" variant is not available either. A row with an expiry is
self-healing: a replica that dies holding a lease loses it when the lease
lapses, with no cleanup path to get wrong.

**The acquire never blocks.** Boot's default `TaskScheduler` has pool size 1 and
`@EnableScheduling` on `SkillsGatewayApplication` is the entire configuration, so
all eight sweeps share one thread. A blocking acquire on the 6-hour re-vet lease
would stall the 5-second webhook poll behind it. `runIfLeader` returns `false`
and logs at DEBUG.

Lease duration is that sweep's own interval, so a lease cannot outlive the gap
it is protecting. `retention-compact` takes twice its interval, because it runs
`git gc` and can legitimately exceed one period.

### The lock goes on the `@Scheduled` wrapper, never the service method

Every sweep already reads its enabled flag *inside* the scheduled method, and
seven of the nine sweep-touching test classes call the *service* method. That
split already exists (`WebhookDispatcher.poll()` vs `dispatchDue()`) and this
change extends it rather than inventing it: leases are a deployment concern, and
a test that wants to exercise a pass should not have to hold one.

`MirrorReconciliationSweep` is the one class where the split does not exist yet.
Its scheduled method keeps the name `sweep` — `ForgeMirrorSweepTests` asserts on
the task registry string containing `"MirrorReconciliationSweep"` and `"sweep"` —
and the unlocked body becomes `sweepNow()`.

### The two defects, fixed

- Move the `waiver-expired` ledger write **after** a successful
  `markExpiryRecorded`, and make the stamp conditional in SQL. The entry is then
  written by whoever won the stamp, once.
- Add a **compare-and-set overload** of `updateCursor` for the export path, and
  leave the unconditional one for replay — `GW_AUDIT_0005` needs the rewind to be
  unconditional, because rewinding *is* the act of overriding the current value.

**What the CAS does and does not do**, stated precisely because the honest
version is narrower than "it stops double delivery": two exporters that both
read cursor `X` both enqueue the batch `X→Y` before either writes, so the CAS
cannot deduplicate the delivery — the *lease* is what prevents two exporters.
What the CAS prevents is the cursor being **clobbered**: a losing exporter, or an
export pass racing an operator's replay rewind, no longer overwrites a cursor it
did not read. The enqueue-then-advance order is left alone; its javadoc's
promise ("a crash in between costs a duplicate, never a gap", `GW_AUDIT_0004`)
depends on it.

### The chart's five-flag block goes; the storage refusal stays

`helm/skills-gateway/templates/_helpers.tpl` keeps the
`storage.backend != "object-store"` refusal **exactly as is**. That one is about
the filesystem backend having no cross-pod locking for *reference transitions* —
a serving-path property on the request thread that no sweep lease touches. The
five-flag block is what the lease makes obsolete, and removing it is what lets
an operator run `replicaCount > 1` on object-store with the gateway's defaults.

`GW_FACADE_0014`'s last clause changes accordingly, and a new requirement carries
the lease itself.

## Impact

- **Schema:** one new table in `V1__init.sql`.
- **Configuration:** none. Lease durations are derived from the intervals that
  already exist; a knob here would be a way to set a lease shorter than the pass
  it protects.
- **API:** none. No endpoint, no DTO, no OpenAPI change.
- **Chart:** `replicaCount > 1` becomes possible on object-store without turning
  the estate's own maintenance off. This is a *relaxation*, and it is only
  defensible because the two racy passes are fixed independently of the lease.

## Out of scope

The three raw `Executors` in the codebase. They are intra-process, and two are
single-thread *by design* — that is precisely why the things inside one JVM do
not race. `VettingService`'s unbounded `newCachedThreadPool` (not a bean, never
shut down) is a real and separate finding; folding a thread-pool lifecycle change
into a locking change makes neither reviewable.
