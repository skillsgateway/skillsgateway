# Proposal: changes to what a marketplace serves happen one at a time

## Why

Every change to what a marketplace serves is a record transition followed by a
reference write:
- an approval's decision and publication;
- a withdrawal, its unpublish and an optional return to the predecessor;
- re-vetting's quarantine;
- the reconciler's repair.

No lock spans the two steps, and the steps of different changes interleave.
A withdrawal landing between an approval's decision and its publication is
then overwritten by the publication. A return to the predecessor, or a repair,
can also republish a snapshot another administrator has just withdrawn.

## What Changes

- New `PublicationLock`: a transaction-scoped PostgreSQL advisory lock per
  marketplace, so it holds across instances.
- **Approval** copies objects into staging first, unlocked. The
  compare-and-set decision and the served-reference transition then run
  together under the lock. A refused publication rolls the decision back in the
  same transaction, which replaces the after-the-fact repair.
- **Withdrawal** runs its transition, its unpublish and any rollback under the
  lock. The rollback target is re-read there; if it is no longer approved, the
  marketplace serves nothing and the ledger records
  `snapshot-rollback-unavailable`.
- **Re-vetting's quarantine** runs under the lock.
- **The reconciler's repair** re-reads the snapshot under the lock and
  republishes only one that is still approved.
- New `GW_APPROVAL_0032 — Changes to what a marketplace serves happen one at a
  time`, verified by `SVC_GW_APPROVAL_0033`.
- Not **BREAKING**: the same endpoints and responses, and one new ledger
  event.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0032`.

## Impact

- New `PublicationLock`. `GitStorage.stage` is split out of `publish`.
- Changed: `ApprovalService.doApprove`, `RevocationService.revoke` and
  `republish`, `RevetService.quarantine`, `PublicationReconciler.republish`.
- Docs: `guides/approving-snapshots.md`, `reference/api/audit.md`.
