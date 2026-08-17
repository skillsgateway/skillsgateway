# Tasks: virtual-catalog

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0061–GW_0063 to `docs/reqstool/requirements.yml`
      (catalog composition; freshness on publication changes; provenance,
      audit and the reserved name).
- [x] 1.2 Add SVC_GW_0061–SVC_GW_0063 to
      `docs/reqstool/software_verification_cases.yml`.

## 2. Backend

- [x] 2.1 `SkillsGatewayProperties.Catalog(enabled=true, name="catalog")`.
- [x] 2.2 `catalog/CatalogService`: serialized idempotent `rebuild()` — fetch
      each serving marketplace's main tip into the catalog repo, compose root
      tree (subtree per marketplace + merged manifest blob), parentless commit
      carrying the constituent list, force-update `refs/heads/main`, prune
      internal refs; `constituents()` parsed from the served commit; empty
      estate → zero-plugin manifest, never 404.
- [x] 2.3 Manifest merge: names `<marketplace>-<plugin>`, sources
      `./<marketplace>/<path>`; deterministic collision winner + warn log.
- [x] 2.4 Rebuild triggers: `ApprovalService.approve` after publish; the
      re-vetting unpublish path after `unpublish`. Rebuild failure logged,
      never propagated to the approval/revocation.
- [x] 2.5 `catalog/CatalogController`: `GET /api/catalog`,
      `POST /api/catalog/rebuild` (audited), OpenAPI annotations.
- [x] 2.6 Registration refuses the reserved catalog name (422).

## 3. API artifacts

- [x] 3.1 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`.

## 4. Tests

- [x] 4.1 Catalog composition: two marketplaces approved → a real `git clone`
      of `/git/catalog` contains both subtrees and a merged manifest with
      prefixed names and rewritten relative sources; a third, held-only
      marketplace is absent.
- [x] 4.2 Freshness: a new approval appears in the catalog without manual
      action; a revocation (enforce mode) removes the marketplace's subtree
      and its plugins from the manifest, and the revoked SHA is unreachable
      from any advertised catalog ref (parentless history).
- [x] 4.3 Empty estate serves a zero-plugin manifest; `GET /api/catalog`
      returns the constituents matching what was cloned; manual rebuild works
      and is audited.
- [x] 4.4 Reserved name: registering a marketplace named `catalog` → 422.
- [x] 4.5 Ledger: catalog clones appear in the fetch ledger under `catalog`.

## 5. Documentation

- [x] 5.1 New guide `docs/manual/guides/virtual-catalog.md`; `mkdocs.yml` nav.
- [x] 5.2 `reference/configuration.md`: the `catalog` block.
- [x] 5.3 `reference/api/`: catalog endpoints; `guides/consuming-skills.md`:
      the one-URL option.
- [x] 5.4 Concepts: lifecycle (derived content), glossary (virtual catalog);
      ARCHITECTURE.md Phase 2 recall note.

## 6. Gates and evidence

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 6.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 6.4 `openspec validate --all --strict`
- [x] 6.5 `mkdocs build --strict`
- [x] 6.6 `evidence.md` with the final commit SHA.
