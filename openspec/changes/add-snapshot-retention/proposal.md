# Proposal: add-snapshot-retention

## Why

Every ingestion records a snapshot row and pins a commit in the marketplace's quarantine
repository (`refs/snapshots/<sha>`), and nothing ever removes either. A marketplace polled
daily accumulates a snapshot per upstream commit forever: held snapshots nobody will ever
review, rejected snapshots kept only as evidence, and the quarantine pack files behind them.
Issue #25 asks for retention policies that reclaim that space on stated criteria, with a
restore window so a mistake is recoverable, and with the vetting guarantees intact — the
gateway may never delete content it is serving.

## What Changes

- **Retention criteria** evaluated per snapshot against a resolved policy:
  - *held too long* — a snapshot still `held` more than `held-max-age` after ingestion
    (unreviewed quarantine backlog);
  - *superseded* — a `held` or `rejected` snapshot of the same marketplace that a later
    **approved** snapshot has overtaken, and that is older than `superseded-min-age`;
  - *last served* — the ledger is consulted as a guard rather than as a criterion: a
    snapshot fetched through the facade within `min-idle` is never eligible. Only approved
    snapshots are ever served, and approved snapshots are categorically ineligible (below),
    so "not fetched in N days" cannot select anything on its own today; `design.md` records
    why and what would make it a criterion.
- **Soft delete with a restore window**: eligibility marks the snapshot deleted
  (`deleted_at`, `deleted_reason`, `purge_after`) without touching its vetting state. It is
  restorable until `purge_after`; restore clears the marks. Administrators can also
  soft-delete and restore a snapshot by hand through the API and the portal.
- **Hard delete as a separate scheduled compaction**: a second pass permanently removes
  snapshots whose restore window has elapsed — the quarantine ref `refs/snapshots/<sha>`
  is deleted with JGit, the repository is garbage-collected so the objects are reclaimed,
  and the row is deleted. The ledger keeps the record of what existed.
- **Hard safety rule**: a snapshot in state `approved` is never eligible, by policy or by
  hand — the manual endpoint rejects it with 409. Team catalogs do not exist in this
  codebase yet; when they do, catalog membership becomes an additional guard on top of
  this one (recorded as a forward-looking note in `design.md`).
- **Configuration in application properties**, globally and per marketplace
  (`skills-gateway.retention.*` with a `marketplaces.<name>.*` override map). The scheduler
  is **off by default**: retention only ever deletes because an operator asked for it.
- **Audit and events**: every policy evaluation outcome, soft delete, restore and hard
  delete lands in the append-only ledger with the acting identity and the reason;
  `snapshot.soft_deleted` and `snapshot.restored` join the lifecycle webhook events.
- Portal: the marketplace detail page shows a snapshot's deleted state with its restore
  deadline, and offers delete/restore controls.
- New requirements GW_0031–GW_0036 with SVC_GW_0031–SVC_GW_0036.

## Capabilities

### New Capabilities

- `snapshot-retention`: criteria-based retention evaluation (GW_0031), soft delete with a
  restore window (GW_0032), the approved/served eligibility guard (GW_0033), scheduled
  hard-delete compaction including git storage (GW_0034), audit records for every retention
  action (GW_0035), and the portal delete/restore surface (GW_0036).

### Modified Capabilities

(none — no existing requirement changes; the snapshot state machine, the facade, and the
approval path are untouched, and every existing endpoint keeps its shape)

## Impact

- New: `retention/` package (`RetentionService`, `RetentionScheduler`, `RetentionController`).
- Changed: `V1__init.sql` (four columns and an index on `snapshots` — the repo keeps a single
  migration), `Snapshot` + `SnapshotRepository` (deletion marks, eligibility queries, purge),
  `SkillsGatewayProperties` (`retention`), `WebhookEvent` (two lifecycle events),
  `ui/src/pages/marketplace-detail.tsx`, `ui/src/api/queries.ts`, generated OpenAPI types,
  MSW handlers, `docs/reqstool/*.yml`.
- Deletion is a new destructive capability: it is opt-in, guarded to non-approved snapshots,
  reversible for the length of the restore window, and fully audited.
