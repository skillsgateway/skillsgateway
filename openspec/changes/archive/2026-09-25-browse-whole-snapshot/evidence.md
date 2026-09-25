# Evidence: browse-whole-snapshot

- **Source state:** `9ca55e9f` on `feat/browse-whole-snapshot`. Every gate below
  was run fresh over that commit, after the last code edit. Only this file and
  the archive follow it.
- **Acceptance criteria:** the SVCs of GW_APPROVAL_0025 to GW_APPROVAL_0028,
  committed with the proposal at `e7e66a39`, before any implementation commit.
- **Not a trust-boundary change.** These reads expose held quarantine content,
  so path handling is tested adversarially. `SnapshotBrowsingTests` asks
  `/tree` for `../../etc`, `/etc`, `wide/..`, `wide/../bulk`, `/wide` and a
  file path, and every one is 404. It narrows `/diff` to `../../etc/passwd`,
  `wide/..`, `/wide` and the name prefix `wid`, and every one returns nothing.
  It searches `/files` for `../` and finds nothing. A negative `offset` is 400
  on all three reads. `RoleEnforcementTests` gains `/tree` in the
  approver-scoped route table and in the refusal walk.
- **Scale:** the fixture is wider than every page. It has a 601-entry
  directory, more than 2,000 files and 603 changed paths, with 600 modified,
  a text file and a binary file added, and a whole directory removed. Every
  paged read is walked to its end by its own `nextOffset`, and each path is
  asserted to appear exactly once.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 847, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  22 passed (22)
[INFO]       Tests  178 passed (178)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:18 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook node_modules/.vite
# runs 1 and 2 failed in collection ("Failed to fetch dynamically imported module", the known harness flake); run 3:
 Test Files  14 passed (14)
      Tests  69 passed (69)

$ (cd src/main/frontend && pnpm e2e)
  22 passed (1.1m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
296/296 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.63 seconds
```

## Verifications by requirement

| Requirement | Verified by |
| --- | --- |
| GW_APPROVAL_0025 — A reviewer can reach every path of a snapshot, one folder at a time | `SnapshotBrowsingTests` (children, statuses, counts, paging, and the not-found shapes); `snapshot-files.test.tsx` (a folder loads when opened, and Show more); the story `FoldersCountTheirChanges`; the e2e explorer spec |
| GW_APPROVAL_0026 — Snapshot listings and the diff state their true totals | `SnapshotBrowsingTests` (root totals, per-directory counts, and a diff summary exact over 603 paths); `snapshot-delta.test.ts`; `snapshot-file-changes.test.tsx`; `snapshot-files.test.tsx` |
| GW_APPROVAL_0027 — Path search over the whole snapshot | `SnapshotBrowsingTests` (the listing paged to its end, a case-insensitive search, and a match beyond page one); `snapshot-files.test.tsx` (the search and its Show more) |
| GW_APPROVAL_0028 — The diff against the served commit is paged, not cut | `SnapshotBrowsingTests` (diff pages, and narrowing to a file, a directory and a traversal shape); `snapshot-file-changes.test.tsx`; the e2e Diff tab assertion |

The e2e explorer spec gained one wait: the inner "hello" folder has to have
loaded before `.last()` picks it. Folders now load when they open, so without
that wait the step raced. No assertion was removed or loosened.
