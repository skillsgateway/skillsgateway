# Design: add-snapshot-retention

## Context

A snapshot is a row in `snapshots(id, marketplace_id, sha, state, violation, created_at,
decided_by, decided_at)` plus one ref in the marketplace's quarantine repository,
`refs/snapshots/<sha>`, pinned by `IngestionService`. `ApprovalService` publishes an
approved snapshot by fetching that ref into the marketplace's *published* repository and
force-updating `refs/heads/main`; the facade only ever opens the published repository. So
one marketplace has exactly two bare repositories, shared by all its snapshots — there is
no per-snapshot directory to delete, and reclaiming space means deleting a ref and letting
git garbage collection drop the objects that become unreachable.

The ingested ref is always the upstream default branch (GW_0017), so "the same
marketplace/ref" from issue #25 is simply "the same marketplace". The ledger (`fetch_log`)
records facade fetches with `marketplace`, `ref` and `sha`, and administrative actions with
`source = 'admin'`; it is append-only and is not touched by this change.

Nothing in the codebase resembles a team catalog today: there is no table, endpoint or
property naming teams, and the only consumer-visible selection of content is the published
`main` ref of a marketplace.

## Goals / Non-Goals

**Goals:** bounded growth of the quarantine repositories and the snapshot table under
operator-stated criteria; a restore window that makes a wrong deletion recoverable; a hard
delete that actually reclaims git storage; an absolute guarantee that served content is
never deleted; a complete audit trail of every retention decision and action
(GW_0031–GW_0036).

**Non-Goals:** deleting or compacting the audit ledger itself (it is append-only, and its
retention is a separate compliance question); archiving snapshot content to cold storage
before deletion; deleting marketplaces; per-plugin or per-skill retention; a DB-backed
policy CRUD surface; reclaiming space in the *published* repository (nothing there is ever
eligible).

## Decisions

- **Deletion is orthogonal to the vetting state, not a fourth state.** `snapshots` gains
  `deleted_at`, `deleted_reason`, `purge_after` and nothing else; `state` keeps its
  `held | approved | rejected` check constraint and its state machine. Adding a `deleted`
  state was rejected: it would erase the vetting outcome (a deleted snapshot was *rejected*
  or *held*, and the ledger and provenance must keep saying so), and it would force
  `SnapshotRepository.decide` to grow transitions that must never exist. As a consequence a
  restore is a single `UPDATE ... SET deleted_at = NULL` and cannot resurrect a snapshot
  into a state it was never in.

- **Three criteria, and one of them is honestly a guard.** Evaluation resolves a policy per
  marketplace and selects:
  - `held-too-long`: `state = 'held' AND created_at < now - held-max-age`.
  - `superseded`: `state IN ('held','rejected')`, older than `superseded-min-age`, and some
    snapshot of the same marketplace with a higher id is `approved`. Restricting supersession
    to non-approved snapshots is deliberate: an older *approved* snapshot is no longer
    published (main was force-updated past it), but it is exactly the kind of content a team
    catalog would pin, and the gateway cannot yet prove nothing references it.
  - `min-idle` (last served): applied to every candidate as a veto —
    `NOT EXISTS (SELECT 1 FROM fetch_log WHERE sha = snapshot.sha AND source <> 'admin' AND
    ts > now - min-idle)`. As a *selection* criterion "not fetched in N days" is inert
    today, because only approved snapshots are ever served and approved snapshots are
    categorically ineligible; it would select real candidates the moment a snapshot can be
    unpublished or a team catalog pins historical approvals, and it earns its place now by
    protecting against exactly that ordering mistake.

- **Approved snapshots are never eligible — checked in SQL, not only in Java.** Every
  eligibility query carries `state <> 'approved'`, and the manual soft-delete endpoint
  refuses an approved snapshot with 409 before anything is written. The published repository
  is therefore untouched by retention: the only ref the compaction deletes lives in
  quarantine. *Forward-looking note*: when team catalogs land, the guard becomes
  `state <> 'approved' AND NOT EXISTS (catalog entry referencing this snapshot)`, which
  extends the same query rather than replacing this design — and only then does relaxing the
  blanket approved-guard become discussable.

- **Two passes, not one.** `RetentionService.evaluate()` soft-deletes; `compact()` hard-
  deletes rows whose `purge_after` has elapsed. They are separate methods on separate
  schedules-by-configuration because the restore window only means something if the two are
  decoupled in time, and because a bug in the criteria costs a reversible mark, never data.

- **Hard delete reclaims git storage with JGit, never a subprocess.** Compaction deletes
  `refs/snapshots/<sha>` with `RefUpdate` (`setForceUpdate(true)`, `delete()`), also clears
  the `refs/quarantine/incoming` staging ref when it points at the purged commit (it is
  force-updated by the next ingest anyway, and leaving it would keep the whole history
  reachable), then runs `Git.gc()` with the expiry set to now so unreachable
  objects go immediately rather than after JGit's default two-week grace. GC runs once
  per marketplace per compaction pass, not once per snapshot. Objects still reachable from
  another snapshot's ref — the common case, since snapshots are commits on one branch — are
  correctly kept; the reclaim is whatever the deleted tip made unreachable.

- **The row goes; the ledger stays.** Compaction ends with `DELETE FROM snapshots WHERE id`,
  after writing a `snapshot-purged` ledger entry carrying the marketplace and the sha. A
  tombstone row was rejected: the append-only ledger is already the durable record of what
  was ingested, decided and purged, and keeping half a snapshot in the table would make
  every listing and every eligibility query carry an extra predicate forever.

- **Policies are properties, globally and per marketplace.** `skills-gateway.retention` holds
  `enabled`, `poll-interval`, `compaction-interval`, `batch-size`, a `defaults` policy
  (`held-max-age` 90d, `superseded-min-age` 30d, `min-idle` 30d, `restore-window` 14d) and
  `marketplaces.<name>` overrides whose unset fields fall back to `defaults`. A DB-backed
  policy table plus CRUD, a portal editor and a migration would triple the surface of this
  change for knobs an operator sets once per environment and reviews in a pull request;
  properties also make the safe default (no retention at all) reviewable in the same place
  as the rest of the deployment. If per-marketplace policies ever need to be edited by
  non-deployers, `retention_policies` slots behind `Retention.policyFor(name)` without
  touching the service.

- **Off by default, and the scheduler is the only automatic path.** `retention.enabled`
  defaults to `false`: an upgrade never deletes anything until an operator opts in. The
  service is still reachable — `POST /api/retention/evaluate` and `POST /api/retention/compact`
  run one pass on demand, and `GET /api/retention/candidates` is a dry run that shows what a
  pass *would* select — so an operator can inspect the effect of a policy before enabling it.

- **Events reuse the lifecycle machinery.** `snapshot.soft_deleted` and `snapshot.restored`
  are ordinary `WebhookEvent.ALL` members emitted through `WebhookService.emit(...)` with the
  existing enqueue-only, signed, retried delivery path — no new event transport. Automatic
  (policy) deletions emit with the actor `retention-policy`, so a receiver can tell an
  operator's action from a scheduled one.

## Risks / Trade-offs

- [A misconfigured policy soft-deletes a large backlog] → the mark is reversible for the
  whole restore window, `GET /api/retention/candidates` previews a pass without writing, and
  the feature is opt-in.
- [Hard delete is irreversible] → it only ever reaches snapshots that were soft-deleted and
  left unrestored for the configured window, never an approved one, and the ledger keeps the
  provenance of what was removed.
- [`Git.gc()` on a large quarantine repository is expensive] → it runs once per marketplace
  per compaction pass, on a schedule an operator sets (default hourly evaluation, six-hourly
  compaction), and only for marketplaces that actually lost a ref in that pass.
- [Deleting the row loses per-snapshot provenance] → provenance for purged snapshots is the
  ledger, which is append-only and now records ingestion, decision, soft delete, restore and
  purge for the same sha.
- [The `min-idle` veto cannot select candidates today] → stated plainly here and in the
  proposal rather than dressed up as a working criterion; it is wired in so that the first
  feature that unpublishes or catalog-pins a snapshot inherits the protection.

## Migration Plan

Additive: `V1__init.sql` gains four nullable columns and one partial index on `snapshots`;
the repo keeps a single migration and every environment builds the schema from scratch. No
existing endpoint changes shape, and with `retention.enabled=false` (the default) the new
schedulers do nothing at all. Rollback is a revert plus dropping the columns.

## Open Questions

(none)
