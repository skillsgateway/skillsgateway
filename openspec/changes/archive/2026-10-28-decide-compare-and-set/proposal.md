# Proposal: a decision lands only on the snapshot state it was made about

## Why

`ApprovalService.doApprove` reads the snapshot, runs its gates on that row, and
then calls `SnapshotRepository.decide`. That re-reads the row by id and
updates `WHERE id = :id`. For an approval of a snapshot that was not decidable
when read, the gates are skipped and `decide` re-reads whatever the state is
by then. Two outcomes follow:
- A withdrawal that lands in between is decided over, and its stamps are
  cleared.
- A rejection racing an approval can overwrite a snapshot that is already
  served.

## What Changes

- `decide` takes the snapshot the caller evaluated. It updates only if the row
  still has that state and the same `revoked_at`; otherwise it refuses with
  `409`, "changed while it was being decided".
- `doApprove` refuses a snapshot that is neither held nor revoked before any
  write, instead of handing it to `decide`.
- `reject` reads the snapshot and decides on that read.
- New `GW_APPROVAL_0031 — A decision lands only on the snapshot state it was
  made about`, verified by `SVC_GW_APPROVAL_0032`.
- Docs: the `409` row in `guides/approving-snapshots.md`.
- Not **BREAKING**: the same endpoints, responses and status codes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0031`.

## Impact

- `SnapshotRepository.decide`, `ApprovalService.doApprove`, `decideGuarded` and
  `reject`.
