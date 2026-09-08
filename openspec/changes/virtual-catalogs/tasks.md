# Tasks: virtual-catalogs

**Nothing here is done.** This PR ships the decision
([ADR 0017](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0017-virtual-catalogs-are-derived-views.md))
and this proposal. The list below is the implementing PR's work, written now so
the size of the slice is visible while the decision is still reviewable.

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0218 (catalog plugin-name collisions withhold every colliding
      entry), GW_0219 (a collision is recorded on the ledger, a metric and the
      catalog API), GW_0221 (every catalog revision's composition is persisted
      and keyed by its commit SHA) and GW_0222 (a catalog revision is answerable
      by SHA after it stops being served) to `docs/reqstool/requirements.yml`
- [ ] 1.2 Add GW_0220 (registration warns when a marketplace name can produce a
      catalog collision) to `docs/reqstool/requirements.yml`
- [ ] 1.3 Add SVC_GW_0218, SVC_GW_0219, SVC_GW_0220, SVC_GW_0221 and
      SVC_GW_0222 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Collision semantics

- [ ] 2.1 Split `CatalogService.mergePlugin` into two passes: accumulate
      `prefixed name → List<(marketplace, plugin)>` across every constituent,
      then emit only single-member entries (GW_0218)
- [ ] 2.2 A within-marketplace duplicate reports against that marketplace rather
      than as a cross-marketplace collision (GW_0218)
- [ ] 2.3 A `CatalogCollision` record carried out of the merge, ordered
      deterministically so the same estate produces the same report
- [ ] 2.4 `catalog-collision` ledger event under the declared system actor in
      `AdminAuditLogger`, one per collision per rebuild (GW_0219)
- [ ] 2.5 A collision counter on `GatewayMetrics` (GW_0219)
- [ ] 2.6 `@Requirements` annotations on the implementing methods

## 3. Revision provenance

- [ ] 3.1 Flyway migration: `catalog_revisions` and `catalog_revision_members`,
      append-only, following the `snapshot_closures` shape (GW_0221)
- [ ] 3.2 `CatalogRevisionRepository` on `JdbcClient` (ADR 0013)
- [ ] 3.3 `CatalogService.rebuild` writes the revision **after** the ref
      transition succeeds; a write failure is logged and does not fail the
      rebuild (GW_0221)
- [ ] 3.4 The commit message keeps its existing constituent lines and
      `served()` keeps parsing them — both written from one in-memory
      composition (GW_0221)

## 4. API

- [ ] 4.1 `GET /api/catalog` gains the served revision's collision list, as
      optional fields on the existing `CatalogInfo` (GW_0219)
- [ ] 4.2 `GET /api/catalog/revisions` — paged, most recent first (GW_0222)
- [ ] 4.3 `GET /api/catalog/revisions/{sha}` — answers for revisions no longer
      served; 404 for an unknown SHA (GW_0222)
- [ ] 4.4 Both routes classified in `MachineApiRegistry` under the existing
      `marketplaces:read` scope — the build fails on an unclassified `/api/**`
      route
- [ ] 4.5 `@Schema` annotations; regenerate `src/main/frontend/openapi.json` and
      `src/api/types.gen.ts`

## 5. Registration warning

- [ ] 5.1 `MarketplaceRegistrationService`: symmetric prefix check against
      existing names, producing a warning naming the specific marketplace
      (GW_0220)
- [ ] 5.2 The warning is an optional response field and a ledger detail; it never
      refuses, and it never fails an `EstateReconciler` entry (GW_0220)

## 6. Tests (adversarial, per the risk-scaled rule)

- [ ] 6.1 A constructed collision pair (`a` + `b-c` against `a-b` + `c`):
      neither entry appears in the merged manifest
- [ ] 6.2 A collision introduced by registering and approving a *later*
      marketplace does not change what a previously-published name resolves to —
      it withholds both
- [ ] 6.3 A collision that disappears when one constituent is revoked: the
      surviving entry returns to the manifest on the revocation-triggered rebuild
- [ ] 6.4 A within-marketplace duplicate is reported against that marketplace
- [ ] 6.5 Non-colliding plugins of a colliding constituent keep working, and the
      constituent stays listed and vendored
- [ ] 6.6 A revision SHA on `fetch_log` resolves through
      `GET /api/catalog/revisions/{sha}` after two further rebuilds have made the
      commit unreachable
- [ ] 6.7 The registration warning does not refuse, and does not fail the
      declarative estate reconciler
- [ ] 6.8 `RefAdvertisementTests` still passes — no new reference reaches the
      wire (GW_FACADE_0016, GW_FACADE_0017)

## 7. Docs (same PR)

- [ ] 7.1 `guides/virtual-catalog.md` — replace the "Known limit" section with
      the specified behaviour, and state plainly that a collision withholds both
- [ ] 7.2 `reference/api/` — the two new endpoints and the enriched
      `GET /api/catalog`
- [ ] 7.3 `guides/registering-a-marketplace.md` — the warning and what to do
      about it
- [ ] 7.4 `reference/observability.md` — the collision metric
- [ ] 7.5 `architecture.md` §13 — the catalog item's *Implemented* note
- [ ] 7.6 A release note for the behaviour change: a plugin published today may
      stop being published in an estate that already has a collision

## 8. Gates and archive

- [ ] 8.1 One fresh run of every gate after the last edit, recorded in
      `evidence.md`
- [ ] 8.2 `opsx:archive` as the final commit
