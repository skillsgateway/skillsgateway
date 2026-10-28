# Design: serve-nothing-persists

## Context

See proposal.md, Why. It builds on the per-marketplace publication lock
(`GW_APPROVAL_0032 — Changes to what a marketplace serves happen one at a
time`), so the marker is written in the same transaction as the withdrawal or
approval that sets or clears it.

## Decisions

### D1 — A per-marketplace marker, not "approvals newer than the last withdrawal"

The alternative compares timestamps, and so it depends on ordering between the
ledger and the rows. One nullable column says exactly what the administrator
decided, and the next approval clears it.

### D2 — Set only for a deliberate or unavoidable "nothing"

`NOTHING`, and a predecessor withdrawn meanwhile, both leave nothing the
administrator wants served. A rollback whose publish failed leaves a
predecessor that is still wanted, so the reconciler should still repair it.

### D3 — Skip the whole marketplace

While the marker stands, nothing approved in that marketplace should be
served, so the reconciler skips the marketplace rather than filtering
snapshots.

## Risks / Trade-offs

- [An administrator wants the predecessor back after choosing `NOTHING`] →
  Approving it again, through the reversal path that takes a second
  administrator, publishes it and clears the marker.
