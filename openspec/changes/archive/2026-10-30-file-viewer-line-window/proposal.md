# Proposal: a finding's line is readable in a file larger than the preview cap

## Why

`GET /snapshots/{id}/file` returns the first 128 KiB of a blob, and the portal
says *line N, beyond the part shown* for a finding past that point (#770). In
`anthropics/skills`, two `system-prompt-disclosure` findings sit at lines 1844
and 2207 of a 346 KB file, and the portal shows only lines 1–1247. The reviewer
has to waive or accept findings whose evidence the gateway will not show them,
and that is exactly where a false positive gets waived blind or a true positive
gets approved. Separately, an empty `path` answers 500.

## What Changes

- New `GW_APPROVAL_0036 — A finding's line is readable in a file larger than
  the preview cap`:
  - `file` takes an optional `line`. For a blob over the cap it returns at most
    128 KiB that starts at a line boundary and contains that line.
  - The response gains `firstLine`, the number of the first line returned. It is
    1 for the head, for a blob within the cap, and when the line is past the end.
  - A blank `path`, or a `line` below 1, is answered with 400.
  - The portal numbers lines from `firstLine`, and following a finding outside
    the part shown loads the part around its line.
- New `SVC_GW_APPROVAL_0037` (the window), `SVC_GW_APPROVAL_0038` (a blank path or line below 1
  is 400), `SVC_GW_APPROVAL_0039` (the portal reaches the line).
- Not **BREAKING**: `line` is optional and `firstLine` is a new field. A request
  without `line` gets today's text.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-preview`: adds `GW_APPROVAL_0036`.

## Impact

- `SnapshotPreviewController.file`, `SnapshotPreviewService.read`, and the
  `FileContent` record.
- `openapi.json` and `types.gen.ts`, regenerated.
- The portal's `useSnapshotFile`, `FileContent` and `SourceView`.
- Docs: `reference/api/marketplaces.md` (`GET /snapshots/{id}/file`) and
  `reference/portal.md` (Contents view).

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
