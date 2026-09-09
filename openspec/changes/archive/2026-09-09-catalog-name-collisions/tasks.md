# Tasks: catalog-name-collisions

Step 0 of `docs/analysis/2026-09-08-architecture-assessment.md` §5 — the defect
its §2.1 found sitting in the backlog. The substitution half of
`virtual-catalogs` slice 1; the revision-provenance half stays there.

## 1. Requirements (SSOT first)

- [x] 1.1 GW_FACADE_0029 — A contested catalog name is published for nobody,
      stating both the withholding and the reporting, because a silent
      withholding only moves the harm
- [x] 1.2 SVC_GW_FACADE_0029
- [x] 1.3 **Domain-prefixed id, not the provisional `GW_0218`–`GW_0222` the
      proposal reserves.** ADR 0018 retired the flat sequence and names this
      change: whoever implements it mints a domain-prefixed id. Confirmed
      GW_FACADE_0029 free on this branch, on `origin/main`, and against every
      in-flight change — `corpus-aware-vetting` and `estate-import-export` are
      both live in the `GW_FACADE_*` block

## 2. Two passes instead of one

- [x] 2.1 Gather claims (`Map<String, List<Claim>>`) during the vendor walk;
      publish nothing while reading
- [x] 2.2 `publishUncontested` emits the single-claimant names and returns the
      rest as collisions
- [x] 2.3 Insertion order preserved for survivors, so a collision-free estate
      produces the manifest it produced before
- [x] 2.4 Vendored subtrees and the constituent list untouched — the withheld
      entry is an advertisement, not a deletion

## 3. Make it audible

- [x] 3.1 Ledger entry per contested name per rebuild, actor `catalog-builder`
      under `ActorType.SYSTEM` — the collision is the gateway's finding, not the
      act of whoever approved the snapshot that triggered the rebuild
- [x] 3.2 `skills_gateway.catalog.collisions` counter, untagged: the contested
      name is unbounded cardinality, and the ledger is where "which" lives
- [x] 3.3 `collisions` on `CatalogInfo`, persisted in the catalog commit message
      beside the constituents — `GET /api/catalog` reconstructs from the served
      commit, so a collision held only in the rebuild's return value would be
      invisible to whoever goes looking afterwards
- [x] 3.4 Regenerate `openapi.json` and `types.gen.ts`

## 4. Tests

- [x] 4.1 A colliding pair loses the name; an uncontested marketplace beside it
      is unaffected; both trees stay vendored; the ledger and the catalog read
      both name it
- [x] 4.2 In `CatalogTests` rather than a new class: the suite is method-ordered
      over a growing estate, and a new `@SpringBootTest` context would breach
      `ContextBudgetTests`'s cap of 32
- [x] 4.3 Fixed marketplace names, not `uniqueName` — the collision is the
      point, and a random suffix cannot arrange one
- [x] 4.4 **Discrimination checked:** restoring first-wins fails the new test.
      A test that passes against the defect it names is decoration

## 5. Docs in the same PR

- [x] 5.1 `guides/virtual-catalog.md` — the "Known limit" section documented the
      defect as a nuisance and is replaced
- [x] 5.2 `reference/api/marketplaces.md` — the `collisions` field
- [x] 5.3 `reference/observability.md` — the counter
- [x] 5.4 `reference/api/audit.md` — the event, and `catalog-builder` added to
      the system actor's principals

## 6. Keep the backlog honest

- [x] 6.1 Note on `virtual-catalogs` recording what shipped here and what
      remains, so the queue stops hiding a defect fix among feature work

## 7. Gates

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `pnpm test:stories`
- [x] 7.3 `pnpm e2e`
- [x] 7.4 `reqstool status local -p docs/reqstool` ends PASS
- [x] 7.5 `openspec validate --all --strict`
- [x] 7.6 `mkdocs build --strict`
- [x] 7.7 `evidence.md`
