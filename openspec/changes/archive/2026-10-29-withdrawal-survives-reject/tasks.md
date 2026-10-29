# Tasks: withdrawal-survives-reject

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0035 and SVC_GW_APPROVAL_0036 in `docs/reqstool/`.

## 2. Keep the withdrawal (SVC_GW_APPROVAL_0036)

- [x] 2.1 `WithdrawnSnapshotRejectTests`: rejecting an administratively
  withdrawn snapshot is refused, naming the administrator, and the withdrawal
  still stands against the commit. A re-vetting revocation is rejected. Watch
  it fail first.
- [x] 2.2 `SnapshotRepository.decide` refuses `rejected` for an
  administratively revoked snapshot (`@Requirements GW_APPROVAL_0035`).

## 3. Documentation

- [x] 3.1 `reference/api/marketplaces.md` and `reference/retention.md`. Verify
  with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
