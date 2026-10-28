# Tasks: vetter-roots-bounded

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0064 and SVC_GW_VETTING_0066 in `docs/reqstool/`.

## 2. Bounded roots (SVC_GW_VETTING_0066)

- [x] 2.1 `PluginRootBoundTests`: 3,000 roots fail within 5 s with
  `too-many-plugin-roots`; an interrupted run over 999 roots and 200,000 files
  stops within 2 s. Watch both fail first.
- [x] 2.2 `SnapshotUnderVetting.read` (overridden by `QuarantineSnapshot` and
  the test snapshot), the root cap and the interruption check
  (`@Requirements GW_VETTING_0064`), with the vetter suites unchanged.

## 3. Documentation

- [x] 3.1 The finding row in `concepts/vetting.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
