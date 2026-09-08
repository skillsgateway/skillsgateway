# Design: mirror-revocation-and-drift

## Context

- `ForgeMirrorService.reconcile()` already computes the served reference set
  from published storage, ls-remotes the mirror, pushes what differs and deletes
  what the mirror still holds inside the served namespaces. It is idempotent by
  construction and reads no repository but `published`.
- `reconcileWithRetries` retries `skills-gateway.mirror.max-attempts` times and
  then **gives up as a designed state**, recording `mirror-push-failed`. The
  first increment's design says plainly what that costs: *"the next approval or
  revocation of that marketplace pushes the whole served set again. What a
  failure costs is freshness until then."*
- The queue is a single daemon thread with an in-memory backlog; a restart drops
  it.
- `MirrorReport` compares both sides live and fails closed on an unreachable
  mirror. `GET /api/mirror/drift` is administrator-only and on
  `MachineApiRegistry`'s unreachable list.
- The repo already schedules this way: `RevetScheduler`, `WaiverExpirySweep`,
  `RetentionScheduler`, `SyncScheduler`, `AuditExportScheduler` — all
  `@Scheduled(fixedDelayString = …)` under the application's `@EnableScheduling`,
  none with leader election.
- `ObjectStoreMetrics` is the precedent for gauges: a `MeterBinder` in
  `observability` reading counters a domain object keeps for its own reasons, so
  the meter names stay in one place and the domain needs no registry to work.

## The failure model this change is answering

The first increment's posture — the gateway's own state is authoritative, the
mirror is flagged as drifted — is right and is not being revisited. What it
leaves open is **duration**. Enumerated:

| Mode | As shipped | After this change |
| --- | --- | --- |
| Revocation's push exhausts its retries (forge down, credential rejected, push refused) | Revoked reference stays on the mirror until the next approval or revocation of that marketplace — possibly never | Removed by the next sweep once the forge is reachable; `stale_refs` non-zero and `seconds_since_success` climbing meanwhile |
| Gateway restarts with a reconciliation queued | Same, silently | Same bound, and the first sweep after startup closes it |
| Somebody pushes to the mirror by hand, inside the served namespaces | Corrected only when something else triggers a reconciliation | Corrected within one interval, and named on the ledger as `mirror-drift-repaired` |
| Forge unreachable indefinitely | WARN log, one ledger row, an endpoint nobody visits | Same, plus a metric an operator alerts on |
| Sweep itself throws | — | Caught in the scheduled method; a `@Scheduled` method that throws is logged and the schedule continues, but relying on that is relying on a framework we do not own |
| Two replicas sweeping at once | — | Both compute the same served set and converge; the push is a force-update to a value both agree on |
| Sweep floods the forge | — | One `ls-remote` per interval when nothing differs, and nothing is pushed when nothing differs |
| The sweep buries the ledger | — | A reconciliation that changed nothing writes no ledger entry (GW_0193) |
| **Published storage answers, and answers wrong** — a transient backend read failure, a half-applied migration, a backend pointed at the wrong prefix or bucket, a marketplace consulted before publication is fully in place | The reconciliation's honest conclusion is "delete every reference on the mirror", and it would do so | Refused before anything is pushed or deleted, recorded as `mirror-reconciliation-refused`, reported as outcome `refused` (decision 3) |

## Decisions

### 1. A scheduled reconciliation, not a durable queue

The first increment listed both. They are not alternatives of equal value.

A durable queue makes the gateway's *own* triggers survive a restart. It does
nothing for the two failure modes that actually matter here: a forge that is
down longer than `max-attempts × retry-delay` (the queue entry is consumed and
the attempt failed — durability does not bring it back), and drift the gateway
never caused. It also adds persistent state, a schema, and a replay ordering
question, to a component whose entire correctness argument is that it holds no
state and replays nothing.

The sweep needs none of that, because reconciliation was already written as
"make the mirror equal the served set now". Running it on a timer is the same
operation with a different trigger. **This is why the change is small and why
that is the point**: the first increment's decision to reconcile rather than
apply deltas is what makes a bounded staleness guarantee cost a scheduler and
nothing else.

The queue therefore stays in memory, and losing it stays bounded.

### 2. Repair, and argue for it

The brief asks whether drift detection should surface a divergence rather than
silently repair it. Repair is right here, for a reason specific to this
component:

- **Leaving a stale reference in place is the failure.** A reference the mirror
  holds and the facade no longer serves is, in the ordinary case, a snapshot the
  gateway revoked. Reporting it and waiting for a human means content the
  gateway has withdrawn stays browsable for as long as nobody reads the report.
  That is the ADR 0008 bypass, arrived at through inaction.
- **The gateway is unambiguously authoritative.** The mirror is defined as a
  copy of published storage. There is no case where the mirror's version is the
  one that should win, so there is no judgement for a human to exercise.
- **A repair cannot reach anything that is not the gateway's.** Both sides are
  filtered through `GitStorage.isServedRef`, so a deletion can only remove a
  reference publication itself would have written. The forge repository's own
  branches, tags, `HEAD` and pull-request refs are outside the served namespaces
  and are neither touched nor reported.

What repair must not be is **silent**, and that is where the "surface it" half
of the concern lands instead: GW_0193. A reconciliation records what it changed;
one that changed the mirror when nothing was approved or revoked is recorded as
drift that was repaired, under its own ledger event, so an operator reading the
ledger can see that somebody pushed to the mirror — which is a security-relevant
fact even though the right response to it was automatic.

The one case where reporting alone survives is the one that is already reported:
a mirror the gateway cannot reach. There is nothing to repair, and `inSync` is
false rather than absent.

### 3. The reconciliation refuses to act on a served set it cannot believe

This is the decision that makes decision 2 safe to run unattended, and it is the one place this
component declines to be authoritative.

The reconciliation deletes whatever the mirror holds that the served set does not. That is correct
exactly as long as the served set is *true*. Consider the read that succeeds and is wrong: an
object-store or filesystem backend returning short after a transient failure, a partially applied
migration, a backend pointed at the wrong prefix, a marketplace consulted before its publication is
fully in place. From inside `reconcile()` that is indistinguishable from a marketplace that
genuinely stopped serving, and the honest conclusion is *delete everything on the mirror*. Once a
timer runs that unattended, it is a silent total wipe filed as a successful repair — worse than the
staleness the timer exists to bound, and reached by the very automation that was supposed to help.

`GitStorage.isServedRef` does not cover this. It bounds *which kinds* of reference may be deleted,
not *how many*, and an empty served set passes it trivially.

**The test is against PostgreSQL, not against a proportion of the mirror.** Before contacting the
forge, the reconciliation asks the gateway's own records how many live approved snapshots the
mirrored marketplace has. If that number is above zero and the served set it just read has no
`refs/heads/main`, the answer is not credible: publication moves the served tip and writes
`refs/snapshots/<sha>` as one all-or-nothing transition (GW_0133), so a marketplace with live
approved snapshots has a tip. The reconciliation then pushes nothing, deletes nothing, records
`mirror-reconciliation-refused`, and reports `refused` as the outcome of the last attempt.

Two alternatives were considered and are worse:

- **A proportional cap** — "refuse when a single run would delete more than N, or more than X% of,
  the mirror's references". There is no threshold that separates a legitimate bulk revocation from
  a degraded read, and the cost of a false refusal is not symmetric: refusing a legitimate mass
  revocation leaves revoked content on the mirror, which is the exact failure this change exists to
  prevent. A cap would trade a rare catastrophic error for a rare *security* error.
- **Refuse on an empty served set, full stop.** It would break revocation propagation on the very
  case that matters most — a marketplace whose last approved snapshot is revoked correctly has zero
  served references, and its mirror must be emptied. The database check is what distinguishes the
  two, and it is why the check is a positive statement about approved content rather than a
  statement about emptiness.

The one false positive is narrow and fails in the right direction: a reconciliation landing inside
the window where a row is already approved and its publication has not yet completed. Nothing is
deleted, the refusal is recorded, and the next reconciliation succeeds.

Refusing here is consistent with repairing in decision 2 rather than in tension with it. The
deletion is authoritative only while the served set is known; a reconciliation that cannot trust
what it read has nothing to be authoritative *with*. Note also that the guard sits in `reconcile()`
and so protects the event-triggered path too — a degraded read during an approval could have wiped
the mirror before this change, and now cannot.

### 4. What happens when the mirror cannot be made consistent

Unchanged in posture, changed in visibility. The revocation still completes, the
facade still stops serving, the push still gives up after its retries and is
still recorded. What is new is that the gateway keeps trying on its own
schedule, and that the divergence is a number:

- `skills_gateway.mirror.stale_refs` — references the mirror holds that are no
  longer served. **This is the alert.** Non-zero for longer than a sweep
  interval means the mirror is showing content the gateway has withdrawn.
- `skills_gateway.mirror.seconds_since_success` — how long since a
  reconciliation last succeeded. Climbing without bound is a forge the gateway
  cannot write to.
- `skills_gateway.mirror.reachable` — 1 or 0 as of the last attempt.
- `skills_gateway.mirror.missing_refs` — served references the mirror lacks.
- `skills_gateway.mirror.reconciliations{outcome=ok|failed}` — counter.

The gauges are updated by the reconciliation and by `report()`, and read from an
in-memory snapshot. They are deliberately **not** computed at scrape time: a
gauge that ls-remotes a forge would put a network call on the scrape path and
would let a monitoring system's polling interval decide how often the gateway
talks to a third party.

The honest limit, stated here and in the guide: `stale_refs` is as fresh as the
last reconciliation. A gateway that cannot reach the forge at all reports
`reachable=0` and a climbing `seconds_since_success`, and cannot know what the
mirror holds. That is why reachability is its own signal rather than being
folded into the drift counts.

### 5. The sweep is on when the mirror is on

`skills-gateway.mirror.sweep-enabled` defaults to true and the sweep does
nothing at all when the mirror is disabled — which is the shipped default, so an
existing deployment is unaffected. An operator who enables a mirror is asking
for a copy that tracks what is served; a mirror that only tracks when an
approval happens to occur, unless you also find and set a second flag, is a
trap.

Interval `15m`, initial delay `1m`. The initial delay is short on purpose: a
restart is exactly when the in-memory queue was lost, so the first sweep after
startup is the one that closes that hole.

The flag exists at all because two of the existing suites assert on drift they
seeded themselves, and a background actor repairing it mid-assertion would make
them race. Those suites set it false, with that reason written where they set
it. That is isolation from a new actor, not a weakened assertion: neither
suite's expectations change.

### 6. No leader election

Every existing scheduler in this repo runs on every replica without one, and for
the mirror it is not merely tolerable but correct: two replicas compute the same
served set from the same published storage and push the same values, so the
worst outcome of a race is a redundant force-update to a value both agree on and
a duplicate ledger row. Deletions are equally convergent — the second replica
finds nothing to delete and therefore writes nothing.

What a race cannot do is push content that is not served, because the served set
is read fresh inside the reconciliation from the only repository the mirror ever
opens.

### 7. `POST /api/mirror/reconcile` blocks, bounded, and that is not a GW_0170 problem

The endpoint enqueues a reconciliation and waits for quiescence up to
`max-attempts × (timeout + retry-delay)`, capped, then answers with a fresh
report. If the wait runs out, the report says so through `pendingUpdates`; the
request never fails because the mirror did.

GW_0170 — *The mirror is never an enforcement path* — is about approval,
revocation and what the facade serves. None of them are on this path. An
administrator who asks the gateway to reconcile a mirror is asking to wait for a
forge, and answering with a stale report instead would defeat the purpose. It is
`POST` rather than `GET` because it changes a remote system.

It is administrator-only and on `MachineApiRegistry`'s unreachable list for the
same reason `GET /api/mirror/drift` is: it names an outbound integration target
and exercises its credential, which is deployment infrastructure rather than
anything the gateway serves.

### 8. Two ledger events, and a third case that writes nothing

- `mirror-updated` — a reconciliation triggered by a publication or a revocation
  that changed the mirror. Its detail now names the references pushed and
  deleted.
- `mirror-drift-repaired` — a reconciliation triggered by the sweep or by an
  administrator that changed the mirror. Nothing was approved or revoked, so
  whatever it changed was divergence, whether left by a push that gave up
  earlier or made on the forge by hand. Same detail shape.
- Nothing — a reconciliation that found the mirror already correct. This matters
  more than it looks: without it a 15-minute sweep would write 96 rows a day per
  gateway into the append-only ledger that is the product, and would drown the
  two events that mean something.

Reference names in the detail are capped (ten each, then a count), because a
marketplace with a thousand snapshots must not put a thousand reference names in
one ledger row.

`mirror-push-failed` is unchanged, and `mirror-reconciliation-refused`
(decision 3) joins it as the other way a reconciliation can end without the
mirror having been confirmed correct.

### 9. Estate configuration — the answer is still no, for the same reason

The first increment argued the mirror is deployment-level outbound integration
configuration, not API-managed runtime state, and nothing here changes that. The
sweep interval is a property in the same category as the retry count. The new
endpoint is an **action**, not an object: it creates, edits and deletes nothing,
and there is no state for `skills-gateway.estate.*` to declare or reconcile.

The question that is still open is the one the first increment recorded: when
the mirror becomes per-marketplace, the mirror repository becomes a property of
the marketplace and `estate.marketplaces` is where it belongs, with the
credential staying a deployment property. This change does not make the mirror
per-marketplace, so it does not answer it.

## Risks / Trade-offs

- **A sweep talks to the forge on a timer.** One `ls-remote` per interval when
  nothing differs. Configurable, and off with the mirror.
- **`stale_refs` is only as fresh as the last reconciliation.** Stated above and
  in the guide; `seconds_since_success` is the companion signal and the reason
  reachability is separate.
- **Repair hides the moment of divergence.** The ledger event and the metric are
  the mitigation: what was repaired is named, but *when it first diverged* is
  bounded only by the sweep interval, not known exactly. An operator who needs
  the exact moment has the forge's own audit log, which is one of the few things
  a forge does better.
- **The blocking endpoint holds a request thread while a forge is slow.** Bounded
  by the mirror's own timeout and attempt count, administrator-only, and one
  task at a time because the executor is single-threaded.

## Verification

Tier 3, per `.claude/skills/old-coder`: this is the revocation path's reach into
an outbound integration.

`ForgeMirrorSweepTests` runs against a real bare repository over `file://`, like
the first increment's suites, and its central walk is adversarial: the mirror is
made unreachable **before** the revocation, so the revocation's own push exhausts
its retries and the mirror is left holding a snapshot the gateway has withdrawn;
the forge is then restored and only the sweep — no approval, no revocation, no
restart — is allowed to run. The assertions are that the reference is gone, that
the ledger names it as a repair, and that the metrics moved. The same suite
covers the no-change case writing nothing, a sweep against a disabled mirror
touching nothing, and a sweep whose forge is still down neither throwing nor
affecting the facade.

The same suite carries the second adversarial case, and it is the one that
matters most for a feature whose failure mode is destructive: the mirrored
marketplace's **published repository** is left answering short — readable, with
its served tip removed — while the database still holds its approved snapshot.
Only the sweep is then allowed to run. The assertions are that the mirror still
holds every reference it held, that the ledger carries the refusal, and that the
report says `refused` rather than `ok`; then the tip is restored and the next
reconciliation is shown to behave normally, so the guard is proved to be a
refusal rather than a permanent jam.

`RoleEnforcementTests` gains the new route in its privileged-write walk, so it
is denied to a no-role session and to an auditor, and `MachineApiRegistryTests`
covers it being unreachable by any scope.
