# Design: catalog-compare-and-set

## Decisions

### D1 — Compare-and-set on `main`, read first

JGit's `RefUpdate` with `setExpectedOldObjectId` refuses with `LOCK_FAILURE`
when the reference is not the expected value at lock time. On the object-store
backend, the conditional put closes the gap between lock time and the write.
Reading `main` before any constituent means any rebuild that commits in
between is detected.

Force stays on, because each revision is parentless and is never a
fast-forward.

### D2 — Recompose, bounded

A lost race means the reading is stale, so the rebuild reads again: retrying
the same commit would be the bug. Five attempts covers any realistic
contention. Past that, the existing failure path applies: the rebuild is
logged and `POST /api/v1/catalog/rebuild` repairs it.

### D3 — A protected seam for the test

`beforeCommit()` is a no-op that a test subclass overrides to interleave a
second instance's rebuild deterministically. A timing-based test would be
flaky by construction.

## Risks / Trade-offs

- [A periodic reconciliation would also catch a rebuild that failed after five
  attempts] → Not added: the failure is loud, and the next publication change
  rebuilds anyway.
