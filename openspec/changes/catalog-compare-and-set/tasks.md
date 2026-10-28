# Tasks: catalog-compare-and-set

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_FACADE_0035 and SVC_GW_FACADE_0037 in `docs/reqstool/`.

## 2. Compare-and-set rebuild (SVC_GW_FACADE_0037)

- [x] 2.1 A `CatalogTests` case (`@Order(6)`, after the ordered estate cases):
  a second `CatalogService` instance reads a served marketplace, which is then
  withdrawn and rebuilt without it, before the instance commits; the served
  catalog must not contain it. Watch it fail first.
- [x] 2.2 `RefTransitions.compareAndSet`, plus the read-first, recompose-on-loss
  loop in `CatalogService.rebuild` (`@Requirements GW_FACADE_0035`). Make 2.1
  green with the catalog and storage suites unchanged.

## 3. Documentation

- [x] 3.1 `guides/virtual-catalog.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
