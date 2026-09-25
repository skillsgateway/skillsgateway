# Tasks: browse-whole-snapshot

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0025, GW_APPROVAL_0026, GW_APPROVAL_0027 and
  GW_APPROVAL_0028, each with its SVC, to `docs/reqstool/`. Verify that
  `reqstool status` lists them. These are the only ids reserved for this change.

## 2. Server reads (SVC_GW_APPROVAL_0025–0028)

- [x] 2.1 `SnapshotPreviewService.tree(id, dir, offset)`: one directory's
  children, each with its status against the served tip and, for a directory,
  its file and change counts. Includes removed paths. Paged at 500.
  Verify with a `PreviewTests` case on a fixture wider than one page: every
  path is reached by walking the pages, and the counts match.
- [x] 2.2 `files(id, q, offset)`: search over full paths with `total` and
  `nextOffset`. Verify with a test that walks every page of a listing larger
  than 2,000 and counts the search matches, and a case-insensitive case.
- [x] 2.3 `diff(id, path, offset)`: pages with `total`, `nextOffset` and a
  whole-diff `summary`, and a `path` narrowing. Verify with a test on more than
  500 changed paths, with exact line counts and a single-path read.
- [x] 2.4 Controller parameters, OpenAPI descriptions, and `400` on a negative
  offset. `/tree` answers `404` for a directory that is not in the tree,
  traversal shapes included. Verify with negative tests, and that the existing
  `PreviewTests` and `RoleEnforcementTests` still pass unchanged.
- [x] 2.5 Regenerate `openapi.json` and `types.gen.ts`. Verify that
  `OpenApiContractTests` passes.

## 3. Portal

- [ ] 3.1 `queries.ts`: infinite queries for the tree, search and diff pages;
  the single-path diff; the decision invalidates the preview reads. Verify with
  `pnpm verify`.
- [ ] 3.2 `SnapshotFileTree` and `SnapshotExplorer`: folders load on open, with
  "Show more" per folder, true totals in the header, and server search. The
  selected file's change is read with `path`. Update `snapshot-files.test.tsx`
  and the story. Verify with the component tests, including a folder wider
  than one page and a search.
- [ ] 3.3 The Diff tab's "Files changed against the served commit", paged with
  inline diffs; the delta line reads `summary`. Verify with component tests and
  a `snapshot-delta` unit test.
- [ ] 3.4 Remove the now-unused client tree building from
  `lib/snapshot-tree.ts` and its unit tests. None of them is an SVC test.
- [ ] 3.5 Run `/impeccable audit` and `/impeccable harden` on the explorer and
  the Diff tab. Fix each finding, or dismiss it in the PR body.
- [ ] 3.6 The e2e explorer spec passes unchanged, and a new e2e assertion for
  the Diff tab's file list is tagged `SVC_GW_APPROVAL_0028`.

## 4. Documentation

- [ ] 4.1 Update `reference/api/marketplaces.md`, `reference/portal.md` and
  `guides/approving-snapshots.md`. Verify with `mkdocs build --strict`.

## 5. Gates and evidence

- [ ] 5.1 Run all six gates fresh after the last code edit. Record the
  commands, the result tails and the SHA in `evidence.md`.
