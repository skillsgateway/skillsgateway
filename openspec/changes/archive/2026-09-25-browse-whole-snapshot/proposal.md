# Proposal: browse-whole-snapshot

## Why

A trial deployment of `0.3.0-b1` reviewed the public repository
`pbakaus/impeccable`, which has 3,261 files (#492). The preview reads stop at
fixed ceilings: the tree listing at 2,000 entries and the diff against the
served commit at 500. A reviewer could open only about 60% of the snapshot they
were asked to approve, and the portal's counts were lower bounds. Approving
content the reviewer cannot open is a correctness problem, not polish.

The owner's decisions on the issue are settled: load the tree one folder at a
time, keep a cap per request but remove the overall ceiling, show true totals,
add path search, and page the diff instead of stopping at 500.

## What Changes

- **A folder at a time.** A new read, `GET /api/v1/snapshots/{id}/tree?dir=`,
  lists one directory's direct children. Each child carries its change against
  the served commit, removed paths included. Each directory child carries the
  number of files beneath it and how many of those changed. The read is capped
  per request and paged with `offset`, so every path of any snapshot can be
  reached.
- **True totals.** Every listing states the full count of what it pages over:
  the directory's files and changes, the matching paths, and the changed paths.
  The diff gains a `summary` over the whole diff, with counts per change type,
  binary entries, and lines added and removed. The snapshot card's delta line
  reads that summary, so its counts are no longer lower bounds.
- **Path search.** `GET /snapshots/{id}/files` gains `q`, a case-insensitive
  substring match over full paths. It also gains `offset`, `total` and
  `nextOffset`, so the flat listing pages too. The portal's filter becomes this
  search, run on the server over the whole snapshot.
- **The diff is paged.** `GET /snapshots/{id}/diff` gains `offset` and `path`.
  `path` narrows the diff to one file or one directory. The portal's Diff tab
  lists every changed file against the served commit, one page at a time, with
  each file's diff inline. The explorer reads the selected file's diff with
  `path` rather than taking it from a full diff.

Every API change is additive. A call with no new parameters returns what it
returned before, plus the new fields. The 128 KiB per-file text cap stays: it
bounds one response, not the snapshot. Content past the cap is stated as
truncated, with the file's full size.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-preview`:
  - GW_APPROVAL_0025 — A reviewer can reach every path of a snapshot, one folder
    at a time
  - GW_APPROVAL_0026 — Snapshot listings and the diff state their true totals
  - GW_APPROVAL_0027 — Path search over the whole snapshot
  - GW_APPROVAL_0028 — The diff against the served commit is paged, not cut

## Impact

- `SnapshotPreviewService` and `SnapshotPreviewController`: the new tree read,
  and paging, search and summary on the existing reads.
- Portal: `snapshot-explorer.tsx`, `snapshot-file-tree.tsx`,
  `snapshot-card.tsx` (Diff tab), `snapshot-delta.ts`, `queries.ts`.
  `lib/snapshot-tree.ts` loses the client-side tree building that the server
  now does.
- `openapi.json` and `types.gen.ts` are regenerated. The change is additive.
- Documentation: the portal reference, the approving-snapshots guide, and the
  marketplaces API reference.
- No schema, configuration, role or estate change. Nothing is added that
  `docs/manual/capability-map.md` would have to absorb: this widens the existing
  preview reads.
