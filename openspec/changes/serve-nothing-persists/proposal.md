# Proposal: a withdrawal's choice to serve nothing outlives a restart

## Why

A withdrawal with `serveAfter=NOTHING` leaves the predecessor approved and
records the choice only on the ledger. The startup reconciler repairs every
approved snapshot that storage does not serve, so on the next start it
republishes the predecessor the administrator chose not to trust.
`GW_APPROVAL_0014` and `GW_APPROVAL_0016` conflict there, and the reconciler
resolves the conflict against the administrator.

## What Changes

- `marketplaces.serving_suspended_at`, folded into `V1__init.sql` as usual
  before 1.0. It is set, inside the publication lock, by a withdrawal that left
  the marketplace serving nothing: either because the administrator chose
  `NOTHING`, or because the predecessor had been withdrawn meanwhile. A
  rollback whose publication merely failed does not set it; that predecessor
  is still meant to be served.
- An approval that publishes clears it, inside the same lock.
- The reconciler skips a marketplace while it is set.
- New `GW_APPROVAL_0033 — A withdrawal's choice to serve nothing outlives a
  restart`, verified by `SVC_GW_APPROVAL_0034`.
- Not **BREAKING**: no API change. Editing `V1` recreates every existing
  database, which is the project's pre-1.0 rule.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0033`.

## Impact

- `V1__init.sql`, `MarketplaceRepository`, `RevocationService.revoke`,
  `ApprovalService.doApprove`, `PublicationReconciler.reconcile`.
- Docs: `guides/approving-snapshots.md`.
