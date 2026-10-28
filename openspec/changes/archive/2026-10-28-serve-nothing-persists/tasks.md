# Tasks: serve-nothing-persists

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0033 and SVC_GW_APPROVAL_0034 in `docs/reqstool/`.

## 2. The marker (SVC_GW_APPROVAL_0034)

- [x] 2.1 `ServeNothingPersistsTests` covers three cases:
  - after a `NOTHING` withdrawal, reconciliation does not re-serve the
    predecessor;
  - after a `NOTHING` withdrawal followed by a new approval, a lost
    publication of that approval is repaired;
  - after a rollback to the predecessor, a lost publication of it is repaired.

  Watch the first fail on unchanged code.
- [x] 2.2 The column in `V1__init.sql`, `MarketplaceRepository.suspendServing`,
  `resumeServing` and `servingSuspended` (`@Requirements GW_APPROVAL_0033`);
  set in `RevocationService.revoke`, cleared in `ApprovalService.doApprove`,
  honoured in `PublicationReconciler.reconcile`.

## 3. Documentation

- [x] 3.1 `guides/approving-snapshots.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
