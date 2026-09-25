# Design

## Context

`SnapshotPreviewService` answers three reads: the flat recursive tree (`/files`,
cut at 2,000), one blob (`/file`, text cut at 128 KiB), and the diff against the
served tip (`/diff`, cut at 500 entries, each with its unified text). The
portal's explorer fetches the whole of `/files` and `/diff`, builds the nested
tree in the browser (`lib/snapshot-tree.ts`), and marks each file's change
there. The delta line on the snapshot card counts lines by parsing the text of
the diff it received. Every one of these stops at a ceiling. See proposal.md,
"Why".

Every path is resolved inside the pinned commit's tree through JGit tree walks.
That is why the reads have no traversal surface (GW_INGEST_0015 — Snapshot file
inspection), and it stays true for everything added here.

## Goals / Non-Goals

**Goals:** every path of any snapshot is reachable in the portal and through the
API. Every count is a true total. Each response stays bounded.

**Non-Goals:**

- Raising or removing the 128 KiB per-file text cap. It bounds one response,
  and a reviewer past it sees "Truncated: showing the first part of N bytes".
  Paging a single blob is not asked for.
- Virtualised rendering. Pages keep the DOM bounded.
- Retiring `/marketplaces/:name/snapshots/:id/files`. The owner agreed it could
  go, but it renders the same explorer and this change does not touch it.
- Rename detection in the diff. It stays off, as before.

## Decisions

### The server lists one directory; the browser stops building the tree

`GET /snapshots/{id}/tree?dir=&offset=` returns a directory's direct children,
directories first and then by name. Each child carries its change against the
served tip. Each directory child carries `files` (files beneath it) and
`changed` (changed paths beneath it, removed ones included). The response
carries the same two counts for `dir` itself, and `total` and `nextOffset` for
paging. The root call's counts are the snapshot's totals.

One recursive walk over the commit tree and the baseline tree, with a
`PathFilter` on `dir`, gives children, statuses and counts together. The walk
is per request and proportional to the subtree. A snapshot of a few thousand
files walks in milliseconds; the cap is on the response, not on the work.

*Alternative:* keep the flat listing and page it, and keep building the tree in
the browser. That was rejected because the browser would have to fetch every
page before any directory's contents could be known to be complete. The
counts per folder, the part a reviewer uses to find the change, would then
still need the whole listing.

*Alternative:* the flat `/files` listing with a `dir` mode. That was rejected
because a directory listing has a different shape (kinds, counts, status), and
one endpoint with two response shapes is harder to read than two endpoints.

### Page sizes

- The tree: 500 children per page. A folder that holds more pages with
  "Show more".
- The flat listing and search: they stay at 2,000 per page, so a caller that
  sends no `offset` sees exactly what it saw before.
- The diff: it stays at 500 entries per page for the same reason. Each entry
  keeps its 128 KiB text cap.

`offset` is a plain index into a deterministic order: tree order for `/files`
and `/diff`, and directories-then-name for `/tree`. A cursor would buy nothing
here, because the listed commit is immutable. The one moving part is the diff's
baseline, which a concurrent approval can move. Every page names its
`baselineSha`, so a caller can tell.

A negative `offset` is `400`. An offset past the end is an empty page with no
`nextOffset`.

### Totals come from the server

- `/files` gains `total`, the number of matching paths.
- `/tree` gains `files`, `changed` and `total`.
- `/diff` gains `total` and `summary`: `added`, `modified`, `removed`,
  `binary`, `linesAdded` and `linesRemoved` over the whole diff, not the page.
  Lines are counted from each non-binary change's `EditList`, so the count is
  exact even where the entry's text is cut. With no baseline, every path is
  `added` and no lines are counted, as before.

`truncated` keeps its name and its meaning on every read: this response does
not hold everything. It is now always paired with `nextOffset`.

The delta line reads `total` and `summary` when they are present. When they
are absent, it keeps the existing client-side count as a fallback.

### Search runs on the server over full paths

`/files?q=` keeps a path when it contains `q`, compared case-insensitively over
the full path, the same rule the portal filter used. A blank `q` is no filter.
The portal debounces the input and renders matches as a flat list of full
paths, with "N matching of M" where M is the snapshot's file count from the
root listing.

### `path` narrows the diff to a file or a directory

`/diff?path=` applies a JGit `PathFilter` to the scan. It is exact for a file
and a prefix for a directory, which is how git treats a pathspec. The explorer
reads the selected file's change this way, and no longer receives the entire
diff to look one entry up. A path that is not in either tree, including every
traversal shape, is an empty diff and not an error. A pathspec is a filter,
not an address. A `dir` for `/tree` that is in neither tree, or that names a
file, is `404`, like `/file`.

### The portal's Diff tab lists the changed files

The Diff tab keeps "Changes since the last approved snapshot" (skills and
plugins). Below it, a new "Files changed against the served commit" section
lists every changed path, one page at a time, with the summary's true totals.
Each file opens its diff inline. Before this change, the explorer's tree
markers were the only place a file-level change could be found.

## Risks / Trade-offs

- **A recursive walk per folder open.** The cost is proportional to the subtree.
  → Walks are local object-store reads. React Query caches each listing per
  snapshot and directory, and the snapshot's content is immutable.
- **Line counts read every changed blob on every page-1 diff read.** This is
  similar to the previous page-1 cost: formatting 500 texts. → For a huge
  change set the summary costs one pass over the changed blobs, and a snapshot
  that big is exactly where the reviewer needs the true number.
- **The baseline moves between pages.** → Every page names its `baselineSha`,
  and the portal invalidates the snapshot's reads on every decision.
