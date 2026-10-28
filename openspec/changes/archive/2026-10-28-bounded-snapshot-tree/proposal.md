# Proposal: a snapshot's tree is walked only within a bound

## Why

`QuarantineSnapshot.index` walks a snapshot's tree recursively and keeps an
entry per expanded path. The ingest-time check for non-regular entries walks
it recursively too. Git stores a subtree once however often it is referenced,
so a commit of about ten objects can describe a hundred million paths. The
external-source budgets already bound resolved sources, but nothing bounded
the marketplace's own tree.

## What Changes

- New `BoundedTree.files` walks non-recursively, entering each subtree
  occurrence and counting every entry, subtrees included. It refuses with
  `TooLarge` past 200,000 entries or 32 directories of depth.
- Ingest uses it for the non-regular-entry check, and a tree past the bound is
  recorded `rejected` with the bound in the violation. That covers the
  upstream tree and any composite tree.
- `QuarantineSnapshot.index` uses it too, failing closed: the vetting run
  records a blocking error rather than expanding the tree.
- New `GW_INGEST_0072 — A snapshot's tree is walked only within a bound on its
  entries and depth`, verified by `SVC_GW_INGEST_0072`.
- Docs: `guides/registering-a-marketplace.md`.
- Not **BREAKING**: no API, schema or configuration change. Marketplaces past
  the bound now land rejected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0072`.

## Impact

- New `BoundedTree`. Changed: `IngestionService` (non-regular-entry check) and
  `QuarantineSnapshot.index`.
