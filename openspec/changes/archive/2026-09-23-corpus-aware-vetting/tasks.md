# Tasks: corpus-aware-vetting

## 1. Requirements (SSOT first)

- [x] 1.1 Add to `docs/reqstool/requirements.yml`: GW_INGEST_0036, GW_APPROVAL_0019,
      GW_APPROVAL_0019.1–.4, GW_APPROVAL_0020, GW_APPROVAL_0021, GW_VETTING_0039
- [x] 1.2 Add their SVCs (GIVEN/WHEN/THEN) to `docs/reqstool/software_verification_cases.yml`

## 2. Schema (SVC_GW_INGEST_0036)

- [x] 2.1 In `V1__init.sql`: `snapshot_facts` (`snapshot_id` primary key, cascade from
      `snapshots`; `facts JSONB` nullable; `built_at`) and `snapshot_plugin_names`
      (`snapshot_id` referencing `snapshot_facts`, `plugin_name`, `location`)
- [x] 2.2 Comment both tables: why `state` is absent, why `facts` may be null, why no
      normalised key is stored

## 3. Facts as recorded state (SVC_GW_INGEST_0036)

- [x] 3.1 `SnapshotFactsService`: `record` writes `build`'s facts without `state`, and the
      plugin-name index, once; `load` returns recorded facts with the current state,
      building when none were recorded. `@Requirements GW_INGEST_0036`
- [x] 3.2 `SnapshotFactsRepository` (new, `policy` package): insert-once, read, the name
      index reads the gate needs
- [x] 3.3 Plugin-name locations: the manifest line of each plugin entry's `name`
- [x] 3.4 `IngestionService`: record after the row is created, before the chain; a failure
      is logged, never rethrown
- [x] 3.5 `PolicyGate` and the rule playground read through `load`; the existing policy SVC
      tests stay green unmodified
- [x] 3.6 `SnapshotFactsPersistenceTests` (`SVC_GW_INGEST_0036`), each shown to fail against a mutant

## 4. Normalisation (SVC_GW_APPROVAL_0019.1)

- [x] 4.1 Vendor `confusables.txt` (Unicode 18.0.0) under `src/main/resources/approval/`
      with a README naming source, version and SHA-256; NOTICE attribution
- [x] 4.2 `NameNormalizer`: NFKC → drop Cf → skeleton/fold to a fixpoint → drop separators
- [x] 4.3 `NameNormalizerTests` (plain JUnit): lookalikes equal, near-misses different,
      idempotent

## 5. The gate (SVC_GW_APPROVAL_0019, .2, .3, .4, SVC_GW_APPROVAL_0020, SVC_GW_APPROVAL_0021)

- [x] 5.1 `SkillsGatewayProperties.Approval`: `nameCollision.enabled`, default true
- [x] 5.2 `NameCollisionGate` (new, `approval` package): ensures the snapshot's index, finds
      its new plugin names, compares with other marketplaces' approved snapshots, consults
      waivers, returns findings and suppressions
- [x] 5.3 `ApprovalService.doApprove`: the gate after policy and before release age; its
      suppressions join the applied waivers; refusals ledgered as `snapshot-approval-refused`
- [x] 5.4 The transition inside a transaction holding one advisory lock, with the gate's
      final evaluation in it; an in-lock refusal is ledgered after the rollback
- [x] 5.5 The vetting override does not lift the gate; restore runs it (same path)
- [x] 5.6 `AdminController`: `GET /api/v1/snapshots/{id}/name-collisions`, the 409 problem
      shape, the approve endpoint's documented 409 reasons
- [x] 5.7 Shared test context sets the rule off; the gate's suites share one enforcing
      context; `ContextBudgetTests` and `ConfigSurfaceBudgetTests` raised by one each with
      the reason written beside the number
- [x] 5.8 `NameCollisionTests` (`SVC_GW_APPROVAL_0019`, `.2`, `.4`, `SVC_GW_APPROVAL_0020`,
      `SVC_GW_APPROVAL_0021`), each shown to fail against a mutant
- [x] 5.9 `NameCollisionRaceTests` (`SVC_GW_APPROVAL_0019.3`): observed failing with the
      lock removed, passing with it

## 6. Chain purity (SVC_GW_VETTING_0039)

- [x] 6.1 `ChainPurityTests`: a re-run over unchanged content is unchanged by estate
      changes; proved non-vacuous with a throwaway estate-reading mutant

## 7. Frontend (GW_APPROVAL_0021)

- [x] 7.1 Regenerate `openapi.json` and `types.gen.ts`
- [x] 7.2 Query hook and a collision notice in the approve dialog, reusing the waiver form
      at snapshot scope only; the confirm button stays shut while a collision is uncovered
- [x] 7.3 A story for the notice, and MSW handler data

## 8. Docs (same PR)

- [x] 8.1 `concepts/vetting.md`: the precondition beside the minimum release age
- [x] 8.2 `guides/approving-snapshots.md`: the refusal and what to do
- [x] 8.3 `guides/waiving-findings.md`: `plugin-name-collision`, and what each scope covers
- [x] 8.4 `reference/configuration.md`: `skills-gateway.approval.name-collision.enabled`
- [x] 8.5 `reference/api/marketplaces.md` (where the snapshot endpoints live): the read endpoint and the 409; `reference/portal.md`: the dialog
- [x] 8.6 `architecture.md`: the T5 row

## 9. Gates and archive

- [x] 9.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`,
      `mkdocs build --strict`
- [x] 9.2 `evidence.md` from one fresh run of all six after the last code edit
- [x] 9.3 Archive the change as the final commit
