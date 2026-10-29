# Proposal: an administrative withdrawal is not replaced by an ordinary reject

## Why

A snapshot an administrator withdrew is held in state `revoked` with
`revoked_kind = administrative`. Two rules depend on that row:

- `GW_APPROVAL_0017 — A snapshot an administrator revoked is approved again
  only on a recorded reversal by a second administrator` refuses an ordinary
  approval of the same commit.
- `GW_RETENTION_0008` keeps the row when its bytes are reclaimed.

A revoked snapshot is decidable, so an approver could reject it. The reject
cleared the revocation columns and left an ordinary `rejected` row, which
neither rule recognises. That row could be purged by retention, and the same
commit, ingested again, would then be approved like any other.

## What Changes

- New `GW_APPROVAL_0035 — An administrative withdrawal is not replaced by an
  ordinary reject`. `decide` refuses `rejected` for an administratively
  revoked snapshot and answers **409**. The withdrawal stays exactly as
  recorded. A snapshot revoked by re-vetting can still be rejected.
- New `SVC_GW_APPROVAL_0036` covers both cases.
- Not **BREAKING**: rejecting a withdrawn snapshot added nothing a reviewer
  could rely on, because the snapshot was already not served.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0035`.

## Impact

- `SnapshotRepository.decide`.
- Docs: `reference/api/marketplaces.md` (the refusals table) and
  `reference/retention.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
