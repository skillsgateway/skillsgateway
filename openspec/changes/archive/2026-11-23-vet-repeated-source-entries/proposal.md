# Proposal: vet every marketplace entry that repeats a plugin source

## Why

The `executable-surface` vetter maps each plugin root to the first manifest
entry that names it, and reads the root once with that entry. A marketplace
entry can declare hooks, MCP and LSP servers, commands, agents and skill
directories inline. A later entry naming the same `source` therefore has its
own declarations skipped. Reproduced on `main` (153e8327): a second entry over
`./p` declaring a `SessionStart` hook that runs `curl … | sh` produced no
finding at all, neither `auto-run-hook` nor `runtime-fetch-exec`. The snapshot
passed vetting with code that runs at every session start and fetches its
payload at run time (#947).

`GW_VETTING_0047 — Hooks that run automatically are flagged with their
trigger` already requires a finding for every hook a marketplace entry
declares. This change makes the code meet it and adds the verification case
that was missing.

## What Changes

- The vetter reads each root with its first entry, as today, and also with
  every later entry naming that root that declares components of its own. An
  entry that declares none reads exactly what the first one reads, so it is
  not read again.
- The surface comparison behind automatic approval (`GW_APPROVAL_0044.8`) is
  not changed: it already refuses any manifest whose entries declare more than
  descriptive fields, so an inline declaration never reaches automatic
  approval. See design.
- A manifest past 1,000 plugins, which ingest refuses but an older snapshot
  can hold, gets the high `too-many-plugin-roots` finding from the vetter
  instead of being read in part.
- The per-tree cache from #938 also holds parsed JSON declaration files, so
  reading one root under many entries parses each file once.
- New `SVC_GW_VETTING_0080` under `GW_VETTING_0047`. The requirement text
  names "any of its marketplace entries" explicitly (revision 0.4.0).
- Not **BREAKING**. A snapshot that hid hooks this way now gets the findings
  it should always have had, which can turn a pass into a warning or a fail.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: `GW_VETTING_0047` gains `SVC_GW_VETTING_0080`.

## Impact

- `PluginComponents` (entry selection, JSON cache), `ExecutableSurfaceVetter`.
- Stop rule: no new package, object type, role, sweep or configuration leaf.
- Trust boundary (vetting): adversarial tests, old-coder discipline.
