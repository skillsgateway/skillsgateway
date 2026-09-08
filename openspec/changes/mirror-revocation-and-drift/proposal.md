# Proposal: mirror-revocation-and-drift

## Why

[#292](https://github.com/skillsgateway/skillsgateway/pull/292) shipped the
first increment of the read-only forge mirror
(`2026-09-07-read-only-forge-mirror`): approved content pushed to a forge,
revocation propagating through reconciliation rather than a second code path,
and `GET /api/mirror/drift` to compare the two. Issue
[#59](https://github.com/skillsgateway/skillsgateway/issues/59) named revocation
propagation and drift detection as the parts that mattered, and both exist.

What does not exist is a **bound on how long a divergence lasts**, and that is
the security-relevant half of the same argument.
[ADR 0008](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0008-serving-surface-stays-the-embedded-facade.md)
keeps the facade canonical precisely so the gateway can withdraw content; a
mirror that keeps serving a revoked snapshot is a way to obtain content the
gateway has withdrawn, which is the bypass the ADR exists to prevent. As
shipped, a revocation whose mirror push exhausts its retries — an unreachable
forge, a rejected credential, a refused push — leaves the revoked reference on
the mirror **until the next approval or revocation of that marketplace**. On a
marketplace nobody touches again, that is forever. The same is true of a
reconciliation lost to a restart (the queue is in memory) and of drift somebody
pushed by hand.

The signals are equally passive: a `mirror-push-failed` ledger row, a WARN log
line, and an admin endpoint somebody has to think to visit. Nothing an operator
can alert on.

The first increment's own out-of-scope list named these: *"a durable retry queue
surviving restart; automatic reconciliation of drift the gateway did not cause
(a scheduled sweep); drift on `GatewayMetrics`"*. This change is that list, plus
the operator affordance the documentation currently cannot offer — a way to make
the mirror consistent now.

## What Changes

- **A scheduled reconciliation (GW_0190).** `MirrorReconciliationSweep` queues
  the same reconciliation on an interval, independently of whether anything was
  approved or revoked. Because reconciliation was already idempotent and already
  computes the served set fresh, this needs no new state and no second code
  path: a failed revocation push, a queue lost to a restart, and a hand-made
  change on the forge all converge within one interval. It subsumes the durable
  retry queue the first increment deferred — a durable queue would replay the
  gateway's own triggers and would still do nothing about the other two cases,
  nor about a forge that is down for longer than the retries last.
- **And a guard, because that deletion now runs unattended (GW_0190).** A
  reconciliation deletes whatever the mirror holds that the served set does not,
  which is correct exactly as long as the served set is true. A read of published
  storage that *succeeds* but answers short — a transient backend failure, a
  half-applied migration, a backend pointed at the wrong prefix — has the honest
  conclusion "delete everything on the mirror", and on a timer that is a silent
  total wipe filed as a successful repair. So before contacting the forge, the
  reconciliation checks the served set against the gateway's own record of what
  that marketplace has approved, and refuses to push or delete anything when the
  two contradict — recording `mirror-reconciliation-refused` and reporting
  `refused` rather than agreement. `GitStorage.isServedRef` does not cover this:
  it bounds which kinds of reference may be deleted, not how many, and an empty
  served set passes it trivially.
- **Telemetry, not just an endpoint (GW_0191).** A `MirrorMetrics` binder
  publishes the number of references the mirror holds that are no longer served,
  the number it is missing, whether the last reconciliation reached it, seconds
  since one last succeeded, and a reconciliation outcome counter. `stale_refs`
  is the alert: it is content the gateway has withdrawn and the mirror still
  shows. Recorded unconditionally through the auto-configured registry, exactly
  as `GatewayMetrics` and `ObjectStoreMetrics` are, so a deployment that turns
  export on gets them with no gateway change. No meter carries a marketplace, a
  SHA or a principal.
- **Reconcile on demand (GW_0192).** `POST /api/mirror/reconcile`,
  administrator-only, queues a reconciliation, waits a bounded time for it, and
  answers with the resulting comparison. This is the concrete answer to "what
  does an operator do about drift", which until now was "approve or revoke
  something, or restart the gateway".
- **A repair is never silent (GW_0193).** The ledger entry for a reconciliation
  now names what changed — the references pushed and the references deleted —
  and a reconciliation that changed the mirror when nothing was approved or
  revoked is recorded as `mirror-drift-repaired`, distinct from the
  `mirror-updated` that follows a publication. A reconciliation that changed
  nothing writes nothing, so a sweep on a 15-minute timer cannot bury the ledger
  the gateway exists to produce.

Repairing rather than only reporting is deliberate and is argued in `design.md`:
for a reference the mirror holds and the facade no longer serves, leaving it in
place *is* the failure, and the reconciliation can only ever delete references
publication itself would have written. What repair must not be is quiet — hence
GW_0193.

## Capabilities

### Modified Capabilities

- `forge-mirror`: gains a bound on how long a divergence can last, telemetry an
  operator can alert on, an administrator-triggered reconciliation, and a ledger
  record of what each reconciliation changed. What the mirror is — a visibility
  copy, never a serving surface, never able to fail an approval or a revocation
  — is unchanged, and every new path here is on the mirror's own thread or on an
  administrator's request.

## Out of scope (named, so the boundary is explicit)

Still deferred from #59, and still deliberately: auto-provisioning of the forge
repository (the gateway calls no forge API); more than one mirrored marketplace,
and with it per-marketplace mirror configuration in `skills-gateway.estate.*`;
README or repository-description injection labelling the mirror read-only; a
machine-API scope reaching either mirror route; and drift in the portal — the
metrics and the two endpoints are the operator surface this change ships, and a
portal panel is a design change with its own review.

The in-memory queue also stays in memory. The sweep is what makes losing it
bounded, which was the whole reason a durable queue was on the list.

## Impact

- **DB**: none. No migration, no schema change, no new table.
- **Backend**: `MirrorReconciliationSweep` and `MirrorMetrics` are new;
  `ForgeMirrorService` gains a reconciliation result, drift statistics, a
  credibility guard and a synchronous-for-the-caller `reconcileNow`, and with
  the guard takes `MarketplaceRepository` and `SnapshotRepository` — its first
  read of the database, and deliberately so: the check is only worth anything
  because it comes from a source the git backend cannot make wrong.
  `MirrorController` gains one route; `MirrorTarget` and
  `SkillsGatewayProperties.Mirror` gain the sweep settings.
- **API**: additive — one new path, `POST /api/mirror/reconcile`, reusing the
  existing `MirrorReport` schema. `src/main/frontend/openapi.json` and
  `src/api/types.gen.ts` regenerated. No existing path, DTO or field changes.
- **Trust boundary**: this is the revocation path's reach into an outbound
  integration → adversarial and negative tests are part of the definition of
  done, and `evidence.md` ships with the change.
- **Docs** (same PR): `guides/read-only-forge-mirror.md`,
  `reference/configuration.md`, `reference/api/mirror.md`,
  `reference/observability.md`.
