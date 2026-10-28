# Proposal: a deleted snapshot is not decided until it is restored

## Why

Retention soft-deletes a held or rejected snapshot and keeps it restorable
for the restore window. Every estate query that matters skips a deleted
snapshot: continuous re-vetting, withdrawal on marketplace removal, and the
name-collision estate. `SnapshotRepository.decide` did not, so a deleted held
snapshot could still be approved and published, and it then sat outside all of
those checks. Rejecting a deleted snapshot also changed a row that retention
had already accounted for.

## What Changes

- New `GW_APPROVAL_0034 — A deleted snapshot is not decided until it is
  restored`. `decide` refuses a soft-deleted snapshot, and its UPDATE also
  requires `deleted_at IS NULL`, so a delete that lands while a decision is in
  flight wins. Approve and reject answer **409** until the snapshot is
  restored.
- New `SVC_GW_APPROVAL_0035` covers three snapshots:
  - a deleted held snapshot is not approved;
  - another is not rejected; both stay held and deleted;
  - a restored one is approved as before.
- Not **BREAKING**: a decision on a deleted snapshot was never a documented
  operation.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0034`.

## Impact

- `SnapshotRepository.decide`.
- Docs: `reference/retention.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
