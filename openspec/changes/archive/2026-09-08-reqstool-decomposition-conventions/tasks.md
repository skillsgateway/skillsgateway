# Tasks: reqstool-decomposition-conventions

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_0158 in `docs/reqstool/requirements.yml` as a parent: the
      capability plus the two cross-cutting contracts (content inside every bound
      is accepted; a refusal names the plugin and the bound)
- [x] 1.2 Add GW_0158.1 – GW_0158.8, one per configurable bound, each with
      `references: requirement_ids: ["GW_0158"]`, its own rationale and its own
      categories
- [x] 1.3 Rewrite SVC_GW_0158 in `docs/reqstool/software_verification_cases.yml`
      to verify the parent's cross-cutting contract rather than all eight bounds
- [x] 1.4 Add SVC_GW_0158.1 – SVC_GW_0158.8 (GIVEN/WHEN/THEN), each claiming only
      what its annotated tests actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ResolutionBudgetTests`: `@SVCs({"SVC_GW_0158"})` stays on
      `content_inside_every_bound_is_accepted` and
      `the_violation_names_the_plugin_and_the_bound_that_was_exceeded`
- [x] 2.2 `ResolutionBudgetTests`: the received-byte and published-bound tests to
      `@SVCs({"SVC_GW_0158.1"})`; the inflated-byte test to `SVC_GW_0158.2`; the
      closure-accumulation test to `SVC_GW_0158.3`; both ratio tests to
      `SVC_GW_0158.4`; object count to `SVC_GW_0158.5`; largest file to
      `SVC_GW_0158.6`; tree depth to `SVC_GW_0158.7`; the deadline test to
      `SVC_GW_0158.8`
- [x] 2.3 `ResolutionBudgetTests` class javadoc records the one-bound-per-child rule
- [x] 2.4 `ExternalSourceResolutionTests`: the received-byte flood test to
      `@SVCs({"SVC_GW_0158.1"})` and the over-budget file test to
      `@SVCs({"SVC_GW_0158.6"})`

## 3. Move the implementation annotations

- [x] 3.1 `ResolutionBudget.expired` → `@Requirements({"GW_0158.8"})`
- [x] 3.2 `ResolutionBudget.accept` → `@Requirements({"GW_0158", "GW_0158.3"})`
- [x] 3.3 `ResolutionBudget.reason` → `@Requirements({"GW_0158.1", "GW_0158.2",
      "GW_0158.4", "GW_0158.5", "GW_0158.6", "GW_0158.7"})`
- [x] 3.4 `ResolutionBudget.maxReceivedBytes` → `@Requirements({"GW_0158.1"})`
- [x] 3.5 `GuardedHttpConnectionFactory.create` → `@Requirements({"GW_0157",
      "GW_0158.1"})`; `Bounded.count` → `@Requirements({"GW_0158.1"})`
- [x] 3.6 `ExternalSourceResolver.resolve` keeps `@Requirements({"GW_0155",
      "GW_0157", "GW_0158"})` — it is the parent capability
- [x] 3.7 `SkillsGatewayProperties.ResolutionBudgets` javadoc names the child
      requirement each setting belongs to

## 4. Spec and gates

- [x] 4.1 Delta spec under `specs/marketplace-ingestion/`: GW_0158 modified,
      GW_0158.1 – GW_0158.8 added, with their SVCs as scenarios
- [x] 4.2 Run every gate one final time and record `evidence.md`
- [x] 4.3 Archive the change as the final commit of the PR
