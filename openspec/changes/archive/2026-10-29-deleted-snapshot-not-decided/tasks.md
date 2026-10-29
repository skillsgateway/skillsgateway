# Tasks: deleted-snapshot-not-decided

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0034 and SVC_GW_APPROVAL_0035 in `docs/reqstool/`.

## 2. Refuse decisions on deleted snapshots (SVC_GW_APPROVAL_0035)

- [x] 2.1 `DeletedSnapshotDecisionTests`: a deleted held snapshot is neither
  approved nor rejected, and stays held and deleted; a restored one is
  approved. Watch it fail first.
- [x] 2.2 `SnapshotRepository.decide` refuses a deleted snapshot, and its UPDATE
  requires `deleted_at IS NULL` (`@Requirements GW_APPROVAL_0034`).

## 3. Documentation

- [x] 3.1 `reference/retention.md`: a deleted snapshot answers 409 to a decision
  until restored. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
