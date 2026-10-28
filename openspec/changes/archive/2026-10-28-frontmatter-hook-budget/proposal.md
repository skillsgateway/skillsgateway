# Proposal: frontmatter hooks are read only within a bounded size

## Why

`SkillFrontmatter` caps the number of alias *events* in a document, not the
size of the graph they describe once dereferenced.
`PluginComponents.frontmatterHooks` then hands that graph to
`ObjectMapper.valueToTree`, which builds one node per path. Twelve levels of
three aliases already give half a million leaves, and a few more levels exhaust
the heap. This runs at ingest, in the executable-surface vetter, and on every
content listing.

## What Changes

- Before `valueToTree`, a recursive count over the parsed hooks value runs with
  a budget of 10,000 values, stopping at the first value past it.
- Over the budget, the file's hooks are reported as a problem ("too large to
  read"), and so as the existing `hook-config-unreadable` finding, without
  building the tree.
- New `GW_INGEST_0071 — Frontmatter hooks are read only within a bounded
  size`, verified by `SVC_GW_INGEST_0071`.
- Not **BREAKING**: no API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0071`.

## Impact

- `PluginComponents.Reader.frontmatterHooks`.
- Docs: the `hook-config-unreadable` row in `concepts/vetting.md`.
