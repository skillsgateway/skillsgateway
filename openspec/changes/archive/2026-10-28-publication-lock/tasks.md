# Tasks: publication-lock

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0032 and SVC_GW_APPROVAL_0033 in `docs/reqstool/`.

## 2. One change at a time per marketplace (SVC_GW_APPROVAL_0033)

- [x] 2.1 `PublicationLockTests` holds the lock from another thread and covers
  three paths:
  - an approval waits, then publishes;
  - a withdrawal that asks to return waits, and does not return to a
    predecessor the holder withdrew;
  - a repair waits, and does not republish a snapshot the holder withdrew.

  Watch all three fail on a stub lock that locks nothing.
- [x] 2.2 `PublicationLock` and `GitStorage.stage`. Approval, withdrawal and
  rollback, re-vetting quarantine and reconciler repair run under the lock
  (`@Requirements GW_APPROVAL_0032`). Make 2.1 green with the approval,
  revocation, re-vetting, reconciliation, catalog and facade suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/approving-snapshots.md` and `reference/api/audit.md` (the new
  event). Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
