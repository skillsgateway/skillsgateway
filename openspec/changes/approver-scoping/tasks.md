# Tasks: approver-scoping

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0056 and SVC_GW_AUTH_0056 in `docs/reqstool/`.

## 2. Scoping (SVC_GW_AUTH_0056)

- [x] 2.1 Two tests:
  - `MarketplaceRemovalTests`: a re-registered name's approver gets `403` on the
    predecessor's snapshot, and an administrator does not;
  - `FetchersScopeTests`: one commit served by two marketplaces reports each
    marketplace's own fetcher only.

  Watch both fail first.
- [x] 2.2 `RoleService.liveName`, `fetchersOf(marketplaceId, sha)` and its two
  callers (`@Requirements GW_AUTH_0056`), with the role, re-vetting, waiver and
  removal suites unchanged.

## 3. Documentation

- [x] 3.1 `reference/api/marketplaces.md` and `guides/registering-a-marketplace.md`.
  Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
