# Proposal: a tree holding a symbolic link or a submodule entry is rejected

## Why

The vetting snapshot reads each blob at the path it appears under and records
no entry type. A git client checks a symbolic link out as a link and a
submodule as another repository. So for a tree holding either, what the
vetters read and what a consumer installs can differ: a link's blob is just its
target path, while the client follows it to the content.

## What Changes

- Ingestion records `rejected` any snapshot whose served tree holds an entry
  other than a regular or executable file. The violation names the path and
  says whether it is a symbolic link or a submodule entry. This covers an
  upstream commit, a hosted push, and a composite with grafted external
  content.
- A tree holding only regular and executable files is held as before.
- New `GW_INGEST_0070 — A snapshot whose tree holds a symbolic link or a
  submodule entry is rejected`, verified by `SVC_GW_INGEST_0070`.
- **Behaviour change for marketplaces that use links:** they now land
  rejected. No API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0070`.

## Impact

- `IngestionService.serve`.
- Docs: `guides/registering-a-marketplace.md`.
