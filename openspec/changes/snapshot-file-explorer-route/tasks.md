## 1. Requirement and traceability

- [x] 1.1 Add GW_INGEST_0032 — Addressable snapshot file inspection to
      `docs/reqstool/requirements.yml`: the system shall present a snapshot's
      pinned contents on an addressable surface that identifies both the snapshot
      and a path within it, shall restore that surface from the address alone,
      shall present every bound of the underlying reads — a cut listing, a
      truncated blob, a binary blob — as a stated condition, and shall apply the
      same role rule as the reads it presents.
- [x] 1.2 Add SVC_GW_INGEST_0032 to `docs/reqstool/software_verification_cases.yml`.
- [x] 1.3 Confirm the id is unclaimed in `openspec/changes/*/` as well as in
      `requirements.yml` before writing it.

## 2. Tree assembly

- [x] 2.1 Add `lib/snapshot-tree.ts`: pure assembly of the flat `FileTree`
      entries plus the `removed` entries of a `SnapshotDiff` into a nested
      structure, directories before files, each sorted by name, every node
      carrying its full path, size and `removed` marker.
- [x] 2.2 Add a filter function over the assembled tree that keeps a file whose
      path matches and every ancestor directory of a kept file, and reports both
      the match count and the size of the set searched.
- [x] 2.3 Unit-test `lib/snapshot-tree.ts` directly: nesting, ordering, a
      single-file snapshot, deeply nested paths, a path that is a prefix of
      another, merged `removed` entries, and a filter that matches nothing.

## 3. The explorer page

- [x] 3.1 Add `components/snapshot-file-tree.tsx`: the collapsible tree, with
      directory expansion state, keyboard operability, and the selected path
      reflected as the active node.
- [x] 3.2 Add `pages/snapshot-files.tsx` reading `:name` and `:id` from the route
      and `path` from the query string, with the two panes each owning their
      scroll and the page itself not scrolling.
- [x] 3.3 Selecting a file pushes `?path=` onto history, so back and forward walk
      the files the reviewer visited.
- [x] 3.4 Render the file pane: text, Markdown via `MarkdownView`, the truncation
      marker, the binary description, and a per-file "vs served" mode fed by the
      indexed `/diff` entries, including the "unchanged" and "no baseline" cases.
- [x] 3.5 Render every page state deliberately: loading, empty snapshot, error,
      forbidden (no approver role), snapshot not in the named marketplace, cut
      listing, and no file selected.
- [x] 3.6 Register the route `/marketplaces/:name/snapshots/:id/files` in
      `main.tsx`.

## 4. The detail page gives the workspace up

- [x] 4.1 Reduce `components/snapshot-preview.tsx` to the inline glance: the
      quick-open paths and an **Inspect contents** link to the route. Remove the
      Files/Diff toggle, the flat path list and the embedded file body.
- [x] 4.2 Update `pages/marketplace-detail.tsx` for the reduced component and
      keep its existing tests passing or updated.

## 5. Tests

- [x] 5.1 `pages/snapshot-files.test.tsx` over MSW handlers: deep link restores
      the selected file, the filter narrows and reports honestly, a truncated
      listing says so, a binary blob is described, a 128 KiB truncation is
      marked, a removed path shows its removal, a 403 renders the forbidden
      state.
- [x] 5.2 `components/snapshot-file-tree.stories.tsx` covering the collapsed,
      opened, removed-path, filtered, dark and keyboard states, run by
      `pnpm test:stories`. No page-level story: Storybook here has no MSW layer,
      and the page's data-driven states are covered by `snapshot-files.test.tsx`
      rather than by exporting internals purely to satisfy the harness.
- [x] 5.3 Extend the e2e spec: from the marketplace detail page, follow **Inspect
      contents**, land on the route, open a `SKILL.md`, and confirm the URL
      carries the path and that reloading it restores the same file — the
      four-eyes property the requirement is about.
- [x] 5.4 Update `components/snapshot-preview.test.tsx` for the reduced component.

## 6. Documentation (same PR)

- [x] 6.1 `docs/manual/reference/portal.md`: the new page, its address, and what
      each state means.
- [x] 6.2 `docs/manual/guides/approving-snapshots.md`: sending a second approver
      a link to the exact file.
- [x] 6.3 `docs/manual/capability-map.md`: the Admin portal row moves from eight
      pages to nine and names the new one.

## 7. Design harness and gates

- [x] 7.1 Run `/impeccable audit`, `/impeccable harden` and `/impeccable critique`
      (new page). Fix or dismiss each finding with a reason in the PR body;
      `design-conventions` and ADR 0003 outrank Impeccable where taste collides.
- [ ] 7.2 Run all gates fresh after the last edit and write
      `openspec/changes/snapshot-file-explorer-route/evidence.md`.
