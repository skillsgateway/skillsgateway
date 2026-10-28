# Proposal: a manifest's plugins are bounded and each source is listed once

## Why

`SnapshotContentService.plugins` walks the whole tree once per manifest plugin
entry, to collect that plugin's skills. Nothing limits the number of entries,
and nothing de-duplicates repeated sources. A 61 KB manifest repeating `./`
2,000 times over 500 skill directories, which git stores once, returned a
million skill entries (46 MB of JSON) in about 15 s. Any signed-in reader can
make that request.

## What Changes

- Ingest records `rejected` a manifest with more than 1,000 plugins.
- The inventory collects every `SKILL.md` in one walk (the bounded walk of
  `GW_INGEST_0072`), and lists each source directory's skills once, under the
  first plugin that declares it. It reads at most 1,000 plugins, which also
  bounds snapshots ingested before this change.
- New `GW_INGEST_0074 — A manifest's plugins are bounded in number and list
  each source once`, verified by `SVC_GW_INGEST_0074`.
- Docs: `guides/registering-a-marketplace.md`.
- Not **BREAKING**: several plugin names over one directory stay accepted, and
  are listed once.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0074`.

## Impact

- `ManifestPolicy.evaluate`, `SnapshotContentService.plugins` and `skills`.
