# Proposal: the executable-surface vetter's work is bounded in plugin roots

## Why

`ExecutableSurfaceVetter` makes every `plugin.json` directory a root, with no
cap, and reads each root in full. Its single-file read walked every path of the
snapshot with `path::equals`, although `QuarantineSnapshot` already holds a
path-to-blob map. So the cost grew as roots × entries, and none of those loops
checked for interruption: a timed-out run kept working.

## What Changes

- `SnapshotUnderVetting.read(path)` is a direct lookup. `QuarantineSnapshot`
  answers it from its map, and the vetter's file access uses it.
- The vetter reads at most 1,000 plugin roots. Past that it raises the
  high-severity `too-many-plugin-roots` finding, so the run fails closed.
- The per-root loop stops when its thread is interrupted, which is what the
  chain does on a timeout.
- New `GW_VETTING_0064 — The executable-surface vetter's work is bounded in
  plugin roots and stops when cancelled`, verified by `SVC_GW_VETTING_0066`.
- Docs: the finding row in `concepts/vetting.md`.
- Not **BREAKING**: no API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0064`.

## Impact

- `SnapshotUnderVetting`, `QuarantineSnapshot`, `ExecutableSurfaceVetter`.
