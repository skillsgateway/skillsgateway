# Tasks: config-surface-guardrail

- [x] 1.1 `ConfigSurfaceBudgetTests` — walk `SkillsGatewayProperties`' record
      components, count leaves, ratchet at the measured number, write
      `target/config-surface.txt`.
- [x] 1.2 Verify the count against the configuration reference, leaf by leaf.
- [x] 2.1 `RemovedProperties` — the map, the `Disposition` enum, the `Removal` record.
- [x] 2.2 `RemovedPropertyGuard` — one pass, all refusals reported together, a
      static `check` seam so both dispositions are exercisable.
- [x] 2.3 Move `skills-gateway.roles.enabled` in, message and behaviour unchanged.
- [x] 3.1 `RoleBootstrapGuard` keeps `GW_AUTH_0026` only; takes `RemovedPropertyGuard`
      as a constructor parameter so the refusal order is wiring, not luck.
- [x] 3.2 Correct `SecurityConfig`'s javadoc, which still explained why the
      machine path does not consult a flag removed in #210.
- [x] 4.1 `RemovedPropertyGuardTests` — the three moved cases, a clean start, both
      dispositions, every refusal together, and the wiring assertion.
- [x] 4.2 Mutation-test each guard against the thing it names.
- [x] 5.1 Gates and `evidence.md`.
