# Evidence: virtual-catalogs (design only)

This PR ships a decision and a proposal. There is no Java, no TypeScript, no
migration and no requirement entry in it, so the gates that exercise those are
not the gates that verify it. What was run, and what was deliberately not, is
recorded honestly below.

Commit under test: `0326b3b` (`docs(decisions): propose ADR 0017 — virtual
catalogs stay derived views`), on branch `docs/virtual-catalogs-design`.

## Gates run

### `openspec validate --all --strict`

```
✓ spec/upstream-sync
✓ spec/vetting-waivers
✓ spec/virtual-catalog
✓ change/virtual-catalogs
Totals: 32 passed, 0 failed (32 items)
```

Exit status `0`. The baseline on `origin/main` before this change was
`31 passed, 0 failed`; this change adds `change/virtual-catalogs`.

### `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 1.59 seconds
```

Exit status `0`. (The red block above this in the raw output is
mkdocs-material's standing MkDocs 2.0 advisory banner, printed on every build in
this repository; it is not an error and does not affect the exit status.)

No retries were needed for either gate.

## Gates deliberately not run

- `./mvnw clean verify`, `pnpm test:stories` and `pnpm e2e` — the container
  runtime on the machine this ran on was saturated by concurrent work, and the
  orchestrating instruction for this task was not to run them. This change
  touches no Java, no TypeScript and no test, so none of the three has anything
  to exercise; a reviewer should still expect CI to run them.
- `reqstool status local -p docs/reqstool` — not run, because this change does
  **not** touch `docs/reqstool/*.yml`. That is deliberate, not an omission: a
  requirement entry with no `@Requirements` annotation and no SVC test is
  incomplete, so writing GW_0218–GW_0222 now would turn the traceability gate red
  on `main` for as long as the implementing PR is open. The ids are reserved in
  `proposal.md` and named in the spec deltas; `docs/reqstool/` is written by the
  implementing PR together with the SVCs and the annotated code.

## Claims in the ADR and where they were checked

The ADR asserts facts about shipped code. Each was read at `331cf68`
(`origin/main` at the time of writing), not recalled:

| Claim | Source |
| --- | --- |
| Catalog plugin name is `marketplace + "-" + plugin`, first in marketplace-name order wins, only signal is `log.warn` | `src/main/java/dev/skillsgateway/server/catalog/CatalogService.java` — `mergePlugin`, and the ascending `Comparator.comparing(Marketplace::name)` in `rebuild` |
| Marketplace names admit `-`, so the map is not injective | `MARKETPLACE_NAME = ^[a-z0-9][a-z0-9_-]*$` in `GitFacadeConfiguration` and `MarketplaceRegistrationService` |
| The collision is documented as a naming curiosity | `docs/manual/guides/virtual-catalog.md`, "Known limit" |
| Catalog commits are parentless and the ref is force-updated; `served()` parses only the current commit | `CatalogService.commitCatalog`, `CatalogService.served`, `storage/RefTransitions.write` |
| Catalog fetches land on `fetch_log` under the name `catalog` | `GitFacadeConfiguration.resolvePublished`, `AuditingPreUploadHook`; `src/test/java/dev/skillsgateway/server/CatalogTests.java` |
| An unscoped PAT reaches every marketplace, and tokens are owner-scoped | `persistence/AccessToken.permitsMarketplace`, `docs/manual/reference/api/tokens.md` |
| `RoleService` is not consulted on the facade path | `GitFacadeConfiguration.resolvePublished`; `docs/manual/guides/delegated-administration.md` |
| The content inventory is derived live from **quarantine** and is not persisted | `ingestion/SnapshotContentService`; `src/main/resources/db/migration/V1__init.sql` has no inventory table |
| Estate reconciles five object types, additively, never destructively | `config/SkillsGatewayProperties.Estate`, `estate/EstateReconciler`, `docs/manual/guides/declarative-estate.md` |
| Group-to-role mapping shipped (#66 closed) | `roles/ClaimRoleMapper`, `openspec/changes/archive/2026-08-23-idp-group-role-mapping/` |
| Registration refuses a supplied ref | `GW_0017 — Gateway-pinned ingestion ref`; `AdminController.DEFAULT_BRANCH`; `DeclaredMarketplace` has no `ref` field |
| `CatalogService.vendor` assumes a filesystem repository | `CatalogService` — `published.getDirectory().getAbsolutePath()`; a `DfsRepository` returns `null` there |

The last row is a defect in shipped code that this change does **not** fix. It is
recorded in the ADR as a prerequisite of the per-team slice rather than absorbed
into this one.
