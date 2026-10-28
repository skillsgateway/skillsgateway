# Proposal: a disabled catalog is not served

## Why

`skills-gateway.catalog.enabled=false` stops the catalog's rebuilds and API
endpoints, and by design keeps its repository. But the facade's resolver
treated the catalog name as always served and never read the flag. So a
disabled catalog went on serving its last revision, including content that
later revocations had withdrawn everywhere else.

## What Changes

- The facade answers a disabled catalog as not found, as it would a
  marketplace that does not exist.
- New `GW_FACADE_0036 — A disabled catalog is not served`, verified by
  `SVC_GW_FACADE_0038`.
- Docs: the `catalog.enabled` entry in `reference/configuration.md`.
- Not **BREAKING**: no API or schema change, and the flag keeps its name and
  default. A deployment that disabled the catalog stops serving it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `virtual-catalog`: adds `GW_FACADE_0036`.

## Impact

- `GitFacadeConfiguration.resolvePublished`.
