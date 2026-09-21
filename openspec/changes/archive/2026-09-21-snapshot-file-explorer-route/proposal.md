# Proposal: snapshot-file-explorer-route

## Why

Approving a snapshot is a decision about its contents, and the surface that
shows a reviewer those contents is the smallest thing on the marketplace detail
page. `SnapshotPreview` renders every path as a flat row in a 320 px scroller
beside an equally small file body, with no directories, no search, and no way to
see a file and its diff at once — inside a page that already scrolls.

The part that matters beyond ergonomics is that **nothing is addressable**.
Approval is four-eyes (GW_APPROVAL_0010 — Separation of duties on snapshot
approval), so two people have to look at the same bytes, and today the only way
to point at a file is "expand snapshot 41, scroll, click the eleventh row". A URL
for "this file in this snapshot" is the deciding reason for this change; the rest
follows from having a surface with room to do the job.

Issue [#448](https://github.com/skillsgateway/skillsgateway/issues/448) carries
the full argument, including why a modal, a separate window and a tab inside the
detail page were each rejected.

## What Changes

- **A dedicated, deep-linkable route** `/marketplaces/:name/snapshots/:id/files`,
  with the selected file in the query string as `?path=…`. Back/forward work, and
  the URL is the thing a reviewer sends to the second approver.
- **A real collapsible tree**, assembled client-side from the flat listing
  `GET /api/v1/snapshots/{id}/files` already returns: directories first, nesting,
  expansion state preserved while moving between files.
- **A path filter** over the loaded tree. It is also the honest answer to
  truncation: "12 matching of 2000 listed paths, and the listing is cut" says
  something the current bare sentence does not.
- **Per-file diff against the served commit**, as a mode of the file pane rather
  than a whole-snapshot toggle that replaces the tree. Files and diff stop
  competing for one box.
- **The existing bounds rendered deliberately**: 128 KiB blob truncation, binary
  blobs described rather than shown, the 2000-path listing cap — each a state the
  page renders on purpose, alongside the loading, empty, error and
  forbidden states the conventions require.
- **The marketplace detail page keeps a glance, not a shrunken workspace**: the
  snapshot row gains an **Inspect contents** link to the route, and keeps the
  quick-open paths (`.claude-plugin/marketplace.json`, the first `SKILL.md`) the
  component already computes.
- **Authorization is unchanged.** The route consumes the same three endpoints,
  each still gated by `requireApproverOfSnapshot`; a session without the role
  sees the refusal rendered as a state rather than a blank pane.
- Requirement GW_INGEST_0032 — Addressable snapshot file inspection, with
  SVC_GW_INGEST_0032.

**No new endpoint, and no new backend package.** Both open questions the issue
left for this design are settled in `design.md` in favour of the existing API:
the 2000-path cap stays, and the tree is assembled in the browser.

## Capabilities

### New Capabilities

_None._ The route presents `snapshot-preview`, which already exists as a
capability and a spec; what is wrong is that its surface cannot carry it.

### Modified Capabilities

- `snapshot-preview`: the reviewer-facing presentation of a snapshot's pinned
  contents becomes addressable — a URL identifies a snapshot and a path within
  it, so a second approver can be sent the exact file rather than instructions
  for finding it — under the same role rule as the reads it presents.

## Impact

- **DB**: none.
- **Backend**: one line, and not the one expected. No new endpoint, no new
  package, and the three preview reads and their authorization are untouched —
  but `SpaController` forwards an explicit list of client routes to the SPA
  entry, so a route missing from it answers 404 the moment anyone opens it
  cold. A pasted link is always cold, which is this change's whole point. The
  route joins that list, and `SpaRoutesTests` now reads the router and fails
  when the two lists disagree.
- **API**: unchanged; `openapi.json` is not regenerated.
- **Portal**: new `pages/snapshot-files.tsx` and
  `components/snapshot-file-tree.tsx`; `main.tsx` gains the route;
  `components/snapshot-preview.tsx` is reduced to the inline glance;
  `pages/marketplace-detail.tsx` links to the route; `lib/snapshot-tree.ts`
  assembles the tree. New stories and tests for the new files.
- **Configuration**: no new settable leaves — `ConfigSurfaceBudgetTests` is
  unmoved. No new Spring test context — `ContextBudgetTests` is unmoved.
- **Trust boundary**: none crossed. This is presentation of reads that already
  exist, with their existing gate; no new read, and no approval path consumes it.
- **Stop rule**: this adds a ninth portal route and no capability. The capability
  map's Snapshot preview row is unchanged and absorbs the behavior as it stands;
  only the Admin portal row's page count moves from eight to nine. The narrowing
  that comes with it: the whole-snapshot Files/Diff toggle is **removed** from
  the detail page rather than kept alongside the new surface, so the detail page
  ends up with less, not more.
- **Docs** (same PR): `reference/portal.md`, `guides/approving-snapshots.md`,
  `capability-map.md`.
- **Design harness**: `/impeccable audit`, `harden` and `critique` — the last
  because this is a new page — per `.claude/skills/design-conventions`.
