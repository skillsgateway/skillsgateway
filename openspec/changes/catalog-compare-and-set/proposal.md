# Proposal: a catalog rebuild never replaces a newer revision with an older reading

## Why

`CatalogService.rebuild` is serialised by a lock that only one instance can
see. It commits through `RefTransitions.write`, which force-updates `main`
with no expected old value. With several instances, a rebuild that read the
estate before a revocation can therefore commit after one that read it
afterwards, and the revoked content is served again through the catalog.

## What Changes

- A rebuild reads the catalog's `main` before reading any constituent. It
  commits with a compare-and-set against that value, using the new
  `RefTransitions.compareAndSet`.
- A rebuild that lost the race composes again from the current estate, up to
  five times, then fails loudly. A failing rebuild is already reported and
  repairable on demand.
- New `GW_FACADE_0035 — A catalog rebuild never replaces a newer revision with
  an older reading`, verified by `SVC_GW_FACADE_0037`.
- Not **BREAKING**: no API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `virtual-catalog`: adds `GW_FACADE_0035`.

## Impact

- `CatalogService.rebuild` and `commitCatalog` (plus a test seam,
  `beforeCommit`), `RefTransitions.compareAndSet`.
- Docs: `guides/virtual-catalog.md`.
