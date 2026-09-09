# Tasks: clamp-the-resolution-budgets

- [x] 1.1 Establish that no leaf is unread (0 of 104) and that "never set" is
      not a safety property; record why in the proposal.
- [x] 2.1 Ceilings on the nine budgets, clamped in `ResolutionBudgets`' compact
      constructor, logged at WARN naming key, configured value and value in force.
- [x] 3.1 `GW_INGEST_0031` + `SVC_GW_INGEST_0031`; annotate the clamp and the tests.
- [x] 4.1 `ResolutionBudgetCeilingTests` — defaults, lowering, raising within
      reach, clamping at the ceiling, and every ceiling above its default.
- [x] 4.2 Mutation-test a missing clamp, a clamp that also applies downward, and
      a ceiling below its default.
- [x] 5.1 Document the ceilings in the configuration reference.
- [x] 6.1 Gates and `evidence.md`.
