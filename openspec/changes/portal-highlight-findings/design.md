# Design: portal-highlight-findings

## Context

The motivation is in proposal.md. The current state:

- **The file explorer.** `SnapshotExplorer` (`snapshot-explorer.tsx`) renders
  the selected file through `FileContent`:
  - Markdown goes through `MarkdownView`.
  - JSON goes through `JsonContent`, with a Formatted/Raw toggle.
  - Everything else is one `<pre>`, with no line numbers.
  - A truncated blob says so, and a binary one is described, not shown.
- **Rendering places.** It renders in the snapshot card's Contents tab
  (`snapshot-card.tsx`), whose address is
  `?snapshot=&tab=&path=` (`useSnapshotAddress` in `marketplace-detail.tsx`).
  It also renders on the standalone `/snapshots/:id/files` route
  (`snapshot-files.tsx`), whose address is `?path=`.
- **The vetting data.** `useSnapshotVetting(id)` returns `VettingView`. It holds
  the run (verdicts, each with its vetter name and `findings[]` of `ruleId`,
  `severity`, `location`, `message`, `content`) and `suppressed[]` (`vetter`,
  `ruleId`, `location`, the waiver and who approved it).
- **Where reports render.** `VettingReport` renders in the card's Vetting tab
  and in `approve-dialog.tsx`. `VettingFlow` shows locations through
  `describeLocations`, as plain text.
- **Theme.** The only severity-shaped token is `--destructive`. The report
  marks high and critical as destructive, and everything else as outline.

## Goals / Non-Goals

**Goals:**

- A reviewer reads each finding on its line, as words, without hovering.
- A location anywhere in the snapshot's vetting report opens its file at its
  line, and the address carries the line.

**Non-Goals:**

- Anything the proposal excludes.
- **Links from `approve-dialog.tsx`.** The dialog is a confirmation step, and
  a link that navigated away from it would abandon the approval in progress.
  Its locations stay text.
- **Syntax highlighting.** The source view is inert text, as the explorer's
  "inspection, not execution" rule requires, with numbers and markers added.

## Decisions

### D1. One pure helper reads findings for the explorer

`lib/file-findings.ts`:

- `parseLocation(location)` returns `{ path, line }`. The line is the digits
  after the last colon, and nothing else, as `WaiverScope.pathOf` reads it.
  Without such a suffix, `line` is null.
- `findingsByPath(view)` returns a `Map<path, FileFinding[]>`. Each
  `FileFinding` is `{ vetter, ruleId, severity, message, line, waived }`.
  `waived` is true when `suppressed[]` holds the same vetter, rule and
  location.
- `highestSeverity(list)` gives the severity for the tree marker.

It is pure, so its tests are plain unit tests. Every consumer uses it, so the
tree, the file and the summary cannot disagree.

### D2. A source view with numbered, marked lines

`components/source-view.tsx` renders text as an ordered list of lines:

- **Line numbers** are text beside each line, `aria-hidden`, so a screen
  reader does not read a number before every line. Each line element has
  `id="L<n>"` and `tabIndex={-1}`, so it can be focused.
- **A marked line** has a severity tint and a left rule: destructive for high
  and critical, primary for medium, and muted for low and info. Its findings
  are rendered **directly below it**, one block per finding, as text:
  `high · executable-surface · runtime-dependency — <message>`. A waived
  finding adds "waived by <who> until <date>", and its tint is dimmed.
- **Colour is never the only signal.** The severity word leads the text.
- **Semantics.** Each block is `role="note"`. The marked line gets
  `aria-describedby` pointing at its blocks, so focusing the line reads its
  findings.

A finding whose line is past a truncated blob's end is not lost. The source
view says "line N is beyond the part shown", with its description.

*Alternative:* a tooltip or popover on a gutter icon. Rejected because the
issue requires the description to be visible in place, and a hover-only
affordance fails keyboard and touch.

### D3. Which view opens

A file with findings opens in the source view:

- Markdown and JSON offer a Source / Rendered (or Formatted) toggle, built
  with the existing `SegmentedGroup`.
- A file without findings keeps today's defaults: Markdown rendered, JSON
  formatted.
- Other text always uses the source view, which adds line numbers to what was
  a bare `<pre>`.

### D4. The summary above the file

"N findings in this file" is a list, one entry per finding:

- The severity, rule and line are a button that moves to the line.
- The message follows.

It is the keyboard route from the top of the file to each marked line. It also
catches a finding whose location has no line, which is listed with "no line",
and a finding beyond a truncated view.

### D5. The `line` address parameter

- **Address:** `useSnapshotAddress` gains `line`
  (`?snapshot=&tab=contents&path=&line=`), and `snapshot-files.tsx` gains
  `?line=` beside `?path=`.
- **Scroll and focus:** `SnapshotExplorer` takes `selectedLine` and
  `onSelectLine`. When the line is set and the file has loaded, the view
  scrolls it into view (`block: "center"`) and focuses it.
- **Selecting a new file** clears `line`. Moving to a line within the same file
  replaces the history entry rather than pushing one, so back returns to the
  previous file, not the previous line.
- **Copy link** now carries the line.

### D6. Links from the report

`VettingReport` and `VettingFlow` take an optional
`locationHref?: (location) => string | null`. The snapshot card passes one
that returns `?snapshot=<id>&tab=contents&path=<p>&line=<n>`. A location
renders as a react-router `<Link>`, so it can open in a new tab, be copied, or
be followed. The approval dialog passes nothing, so its locations stay text.

A group with several locations links each of them. `describeLocations` keeps
its "and N more" truncation, and gains a link variant.

### D7. The tree marker

`SnapshotFileTree` takes an optional `findings: Map<path, FileFinding[]>`.
A file row that carries findings shows its highest severity and count
("2 high") beside its existing status slot. Those are text, with the same
colour mapping. Directories carry no marker.

## Risks / Trade-offs

- **A large file in the source view.** A blob is bounded by the explorer's
  existing truncation, and the source view renders only what the blob
  returned. Lines are plain elements. A list of a few thousand lines renders
  in well under a frame budget, so virtualisation is not added until it is
  needed.
- **A per-file finding marks one line and names the rest** (#532). The message
  says "lines 6, 7, 11", and only line 6 is tinted. A structured line list
  would need a `Finding` change, which is declared a non-goal.
- **An external vetter with a free-text location.** It has no line, so it is
  listed in the summary under its path, if the text parses to a path, and
  nothing is marked.
- **The report moves on.** A re-run replaces the findings, and the source view
  re-renders from the same query, so a link with a stale line still opens the
  file. The line is only focused if it still exists.

## Migration Plan

None. It is a portal-only change. `openapi.json` is unchanged, and there is no
schema change.
