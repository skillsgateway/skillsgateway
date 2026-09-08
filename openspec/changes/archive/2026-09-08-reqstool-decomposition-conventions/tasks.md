# Tasks: reqstool-decomposition-conventions

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_INGEST_0026 in `docs/reqstool/requirements.yml` as a parent: the
      capability plus the two cross-cutting contracts (content inside every bound
      is accepted; a refusal names the plugin and the bound)
- [x] 1.2 Add GW_INGEST_0026.1 – GW_INGEST_0026.8, one per configurable bound, each with
      `references: requirement_ids: ["GW_INGEST_0026"]`, its own rationale and its own
      categories
- [x] 1.3 Rewrite SVC_GW_INGEST_0026 in `docs/reqstool/software_verification_cases.yml`
      to verify the parent's cross-cutting contract rather than all eight bounds
- [x] 1.4 Add SVC_GW_INGEST_0026.1 – SVC_GW_INGEST_0026.8 (GIVEN/WHEN/THEN), each claiming only
      what its annotated tests actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ResolutionBudgetTests`: `@SVCs({"SVC_GW_INGEST_0026"})` stays on
      `content_inside_every_bound_is_accepted` and
      `the_violation_names_the_plugin_and_the_bound_that_was_exceeded`
- [x] 2.2 `ResolutionBudgetTests`: the received-byte and published-bound tests to
      `@SVCs({"SVC_GW_INGEST_0026.1"})`; the inflated-byte test to `SVC_GW_INGEST_0026.2`; the
      closure-accumulation test to `SVC_GW_INGEST_0026.3`; both ratio tests to
      `SVC_GW_INGEST_0026.4`; object count to `SVC_GW_INGEST_0026.5`; largest file to
      `SVC_GW_INGEST_0026.6`; tree depth to `SVC_GW_INGEST_0026.7`; the deadline test to
      `SVC_GW_INGEST_0026.8`
- [x] 2.3 `ResolutionBudgetTests` class javadoc records the one-bound-per-child rule
- [x] 2.4 `ExternalSourceResolutionTests`: the received-byte flood test to
      `@SVCs({"SVC_GW_INGEST_0026.1"})` and the over-budget file test to
      `@SVCs({"SVC_GW_INGEST_0026.6"})`

## 3. Move the implementation annotations

- [x] 3.1 `ResolutionBudget.expired` → `@Requirements({"GW_INGEST_0026.8"})`
- [x] 3.2 `ResolutionBudget.accept` → `@Requirements({"GW_INGEST_0026", "GW_INGEST_0026.3"})`
- [x] 3.3 `ResolutionBudget.reason` → `@Requirements({"GW_INGEST_0026.1", "GW_INGEST_0026.2",
      "GW_INGEST_0026.4", "GW_INGEST_0026.5", "GW_INGEST_0026.6", "GW_INGEST_0026.7"})`
- [x] 3.4 `ResolutionBudget.maxReceivedBytes` → `@Requirements({"GW_INGEST_0026.1"})`
- [x] 3.5 `GuardedHttpConnectionFactory.create` → `@Requirements({"GW_INGEST_0025",
      "GW_INGEST_0026.1"})`; `Bounded.count` → `@Requirements({"GW_INGEST_0026.1"})`
- [x] 3.6 `ExternalSourceResolver.resolve` keeps `@Requirements({"GW_INGEST_0023",
      "GW_INGEST_0025", "GW_INGEST_0026"})` — it is the parent capability
- [x] 3.7 `SkillsGatewayProperties.ResolutionBudgets` javadoc names the child
      requirement each setting belongs to

## 4. Spec and gates

- [x] 4.1 Delta spec under `specs/marketplace-ingestion/`: GW_INGEST_0026 modified,
      GW_INGEST_0026.1 – GW_INGEST_0026.8 added, with their SVCs as scenarios
- [x] 4.2 Run every gate one final time and record `evidence.md`
- [x] 4.3 Archive the change as the final commit of the PR
