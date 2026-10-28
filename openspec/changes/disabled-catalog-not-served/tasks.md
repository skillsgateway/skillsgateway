# Tasks: disabled-catalog-not-served

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_FACADE_0036 and SVC_GW_FACADE_0038 in `docs/reqstool/`.

## 2. Not served while disabled (SVC_GW_FACADE_0038)

- [x] 2.1 `DisabledCatalogTests`: a disabled catalog that storage still holds
  answers as not found; an enabled one is served. Watch the first fail.
- [x] 2.2 The check in `resolvePublished` (`@Requirements GW_FACADE_0036`), with the
  catalog and facade suites unchanged.

## 3. Documentation

- [x] 3.1 `reference/configuration.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
