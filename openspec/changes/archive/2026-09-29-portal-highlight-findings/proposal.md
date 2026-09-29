# Proposal: portal-highlight-findings

## Why

Issue [#531](https://github.com/skillsgateway/skillsgateway/issues/531). A
reviewer reads a finding as text: `executable-surface · runtime-dependency ·
plugins/x/skills/y/SKILL.md:136`. To see the line, they open the Contents tab,
find the file in the tree, and scroll to line 136 of a view that has no line
numbers. The finding already knows exactly where it is. Review is the one
place the gateway asks a human to judge content, so the evidence should be
where the content is.

## What Changes

- **Findings marked in the file.** In the Contents tab, a file's lines that
  vetter findings locate are marked by severity. **Each marked line shows the
  finding's description in place**: vetter, rule, severity and message. It is
  text, not only a colour, and it is reachable by keyboard and screen reader,
  not on hover. Several findings on one line are all shown, and a waived
  finding says it is waived. The marks are in a line-numbered source view.
  Plain text opens in it. Markdown and JSON open rendered, and following a
  finding opens the source (design D3).
- **A finding summary per file.** Above the file, "N findings in this file"
  lists each finding with a link to its line. That is the keyboard route from
  the top of the file to each marked line.
- **Findings reachable from the tree.** A file that carries findings is marked
  in the file tree, with the highest severity and the count.
- **Links from the report.** Every `path:line` location in the vetting report
  and the vetting flow becomes a link to that file and line in the Contents
  tab. The address gains `line`
  (`?snapshot=&tab=contents&path=&line=`), which scrolls to and focuses the
  line, so a link sent to the second approver opens on the evidence.
- **No backend change.** Everything is read from the existing
  `GET /api/v1/snapshots/{id}/vetting`: the run's findings with their
  `path:line` locations, and `suppressed` for what is waived.

**Not in scope:**

- A structured list of lines on `Finding`. A per-file finding from #532 names
  its other lines only in its message, so only its located line is marked,
  and the message says the rest.
- Column ranges.
- Changing the external connector contract's `location` wording
  ([ADR 0009 — The external vetting connector contract](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0009-external-vetting-connector-contract.md)).
  A location that does not parse as `path:line` is listed in the file summary
  without a line, and is not marked.
- Directory-level markers in the tree.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-preview`:
  - GW_APPROVAL_0029 — A file in the Contents view marks the lines vetter
    findings locate, each with its description
  - GW_APPROVAL_0030 — A finding's location links to its file and line in the
    Contents view

## Impact

- **Portal:** `snapshot-explorer.tsx` (the line-numbered source view,
  markers, the per-file summary and the view toggle), `snapshot-file-tree.tsx`
  (file markers), `vetting-report.tsx` and `vetting-flow.tsx` (location
  links), and `marketplace-detail.tsx` and `snapshot-card.tsx` (the `line`
  address parameter). There is a new pure helper to parse a location and group
  findings by path, mirroring `WaiverScope.pathOf`.
- **API and backend:** none. `openapi.json` is unchanged.
- **Docs:** `reference/portal.md` (the Contents tab, the address, and the
  links in the vetting report) and `guides/approving-snapshots.md` (reading a
  finding in context).
- **Design harness:** `/impeccable audit` and `/impeccable harden` on the
  Contents tab and the vetting report, as design-conventions requires.
- **Stop rule:** no new package, estate object type, role, sweep or
  configuration leaf. It narrows the distance between two surfaces that
  already exist.
