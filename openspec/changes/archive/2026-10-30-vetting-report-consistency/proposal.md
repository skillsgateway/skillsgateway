# Proposal: the vetting report agrees with itself

## Why

On real marketplaces the vetting report contradicts itself (#775). A step card
says "3 findings" (groups) while the vetter section below it says "18
finding(s)" (raw findings), and the headline adds the two kinds together. A
vetter whose every blocking finding is waived stays red "fail" while the outcome
reads "clear with waivers". A finding that names several lines
(`… (lines 81, 86)`) marks only the first one in the file view. A reviewer has to
work out which of these is true before deciding anything.

## What Changes

- New `GW_VETTING_0070 — The vetting report counts findings and finding groups
  in one wording`: the step card, the headline, the "stopped at" reason and the
  vetter section all say "N findings", or "N findings in M groups" when grouping
  collapsed some.
- New `GW_VETTING_0071 — A vetter whose every blocking finding is waived reads
  as waived, not failed`: its step and its section read "waived" in the outcome's
  colour, say what it recorded, and it is no longer listed as objecting.
- New `GW_VETTING_0072 — The file view marks every line a finding names`: a
  finding whose message lists lines marks each of them, not only the line its
  location names.
- A long finding message wraps aligned with the rule and location of its row in
  every finding list (report rows, step detail, blocking list). Layout only.
- **Parked: showing the matched text.** No finding carries the matched excerpt,
  and a redacted excerpt for secret rules needs new storage and a new API field.
  The finding row already links to its line in the file view. See design.md.
- Not **BREAKING**: no API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0070` and `GW_VETTING_0071`.
- `snapshot-preview`: adds `GW_VETTING_0072`.

## Impact

- Portal only: `lib/vetting-flow.ts`, `lib/file-findings.ts`,
  `components/vetting-report.tsx`, `components/vetting-flow.tsx`,
  `components/source-view.tsx` (one loop), their tests and stories.
- Docs: `reference/portal.md` and `guides/approving-snapshots.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
