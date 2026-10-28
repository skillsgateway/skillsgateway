# Tasks: decide-compare-and-set

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0031 and SVC_GW_APPROVAL_0032 in `docs/reqstool/`.

## 2. Compare-and-set decision (SVC_GW_APPROVAL_0032)

- [x] 2.1 `DecisionCompareAndSetTests` covers four cases:
  - an approval read before an administrative withdrawal is refused, and the
    withdrawal stands;
  - a rejection read before an approval is refused, and the approval stands;
  - a revoked read before re-approval and a second withdrawal is refused;
  - an undisturbed decision lands.

  Watch the first fail on a stub that keeps today's behaviour.
- [x] 2.2 `decide(Snapshot expected, …)` with the compare-and-set, the
  up-front refusal in `doApprove`, and `reject` deciding on its read
  (`@Requirements GW_APPROVAL_0031`). Two existing tests pass the snapshot
  record instead of its id, with their assertions unchanged.

## 3. Documentation

- [x] 3.1 `guides/approving-snapshots.md` names the new `409` cause. Verify with
  `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
