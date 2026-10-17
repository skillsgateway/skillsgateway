## Why

axe-core 4.14 added the `label-content-name-mismatch` rule (WCAG 2.5.3, Label in
Name): a control's accessible name must contain its visible text. Renovate's lock
file maintenance (#646) pulls 4.14 in, and the Storybook story tests, which run axe
with violations as errors, fail 27 stories in five files. The failures are real: four
portal components compose `aria-label` by hand, apart from the text they render, so
a speech-input user who says what they see ("Clear override") does not match the name
the control carries ("Clear every chain override on …").

Until this lands #646 cannot merge, and holding `axe-core` back would hide the
defect rather than fix it.

## What Changes

The accessible name of each affected control is built from the same pieces that are
rendered, so it contains the visible text by construction and only adds context after
it:

- **Snapshot file tree** — directory and file rows. The visible markers are terse
  (`2`, `1 changed`, `high · 2`, `4 KiB`); the label spells them out but in a different
  order and wording.
- **Vetting flow stages** — each stage button shows an eyebrow, label, state, meta line
  and chips; the label carries only label and state.
- **"Re-run the chain now"** — labelled "Re-run the vetting chain on snapshot N".
- **"Clear override"** (estate vetting table) — labelled "Clear every chain override
  on <marketplace>".

No visible text changes, no behaviour changes, no API or schema change. The accessible
names do change, and the component tests, stories and the e2e spec that query by those
names move with them.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. This change sets `skip_specs: true` and every requirement keeps its exact
meaning; it brings four components into line with a rule that already exists (the
design conventions' "Storybook stories run axe with violations as errors"). Minting a
requirement id for it would be accumulated scope, so none is minted. The surfaces
concerned are covered by `GW_APPROVAL_0025 — A reviewer can reach every path of a
snapshot, one folder at a time`, `GW_INGEST_0032 — Addressable snapshot file
inspection`, `GW_VETTING_0031 — Portal vetting chain flow`, `GW_VETTING_0005 — Portal
vetting surface` and `GW_VETTING_0035 — Estate-wide portal governance of the vetting
chain`.

## Impact

- `src/main/frontend/src/components/snapshot-file-tree.tsx`, `vetting-flow.tsx`,
  `vetting-report.tsx` and `pages/vetting.tsx`, plus `lib/vetting-overrides.ts`, which
  builds the "Clear every chain override" string.
- Tests that query the old names: `vetting.test.tsx`, `vetting.stories.tsx`,
  `chain-staleness.stories.tsx`, `vetting-report.test.tsx`, `e2e/portal.spec.ts`.
- Unblocks #646 (lock file maintenance). Nothing else depends on it.
- No documentation page names these controls' accessible names; `docs/manual/` is
  expected to need no change (task 5 confirms).
