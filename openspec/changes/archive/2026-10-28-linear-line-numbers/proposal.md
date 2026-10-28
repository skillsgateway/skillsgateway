# Proposal: locating findings and following imports take linear time

## Why

`ContentRules.lineOf` and `HookModules.lineAt` rescan the file from offset 0
for every match, so the cost is matches × file length. `HookModules.IMPORT_FROM`
also starts a lazy scan to the next `;` or quote from every `import` or
`export` keyword. A file of a megabyte, below the scan limit, can therefore
outlast a vetter's time limit, or hold the role-free content listing on the
request thread for seconds.

## What Changes

- New `LineIndex`: newline offsets indexed once per file, and a binary search
  per lookup. It replaces the per-match rescans in `ContentRules.apply`,
  `SecretScanVetter` and `HookModules`.
- Import discovery anchors on `from '…'` and looks back to the previous `;` or
  quote for the keyword. The look-back spans are disjoint, so the pass is
  linear and finds the same specifiers as before.
- New `GW_VETTING_0063 — Locating findings and following imports take time
  linear in the file`, verified by `SVC_GW_VETTING_0065`.
- Not **BREAKING**: findings, locations and imports are the same.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0063`.

## Impact

- New `LineIndex`. Changed: `ContentRules`, `SecretScanVetter`, `HookModules`.
