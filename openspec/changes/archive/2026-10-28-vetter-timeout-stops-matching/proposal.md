# Proposal: a built-in vetter that outruns its time limit stops working

## Why

The chain cancels a vetter that exceeds its time limit with
`future.cancel(true)`. That only sets the thread's interrupt flag, and
`java.util.regex` never reads it. So a pattern that backtracks on crafted
content keeps running on an abandoned thread after the timeout is recorded. Each
such snapshot holds a core until restart, and the executor is unbounded.

## What Changes

- Every pattern a built-in vetter runs over snapshot content, including the
  hook-module reader, matches through `InterruptibleText`. That is a
  `CharSequence` that throws once its thread is interrupted, so cancellation
  ends the match at the next character read.
- The dependency-manifest section header pattern becomes possessive, so a
  header that never closes is read in linear time. The header's name is
  trimmed in Java instead.
- New `GW_VETTING_0062 — A built-in vetter that outruns its time limit stops
  working`, verified by `SVC_GW_VETTING_0064`.
- Not **BREAKING**: no API, schema or configuration change. Verdicts are
  unchanged: a timeout is still an `ERROR` that blocks
  (`GW_VETTING_0002 — Fail-closed vetting chain aggregation`).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0062`.

## Impact

- New `InterruptibleText`. The match sites in `RuntimeFetch`,
  `DependencyManifests`, `ContentRules`, `SecretScanVetter`, `LicenseDetector`,
  `MarkdownFences`, `ExecutableSurfaceVetter` and `HookModules`.
- Docs: `concepts/vetting.md`.
