# Evidence: invocation-adoption-metrics

The design PR shipped ADR 0016 and this proposal with no code. Its evidence was
CI on that branch. This file records the implementing PR for issue #85.

**Commit under test:** the tip of `feat/85-skill-presence-inventory`, branched
from `5539cbb`.

## Gates, one fresh run after the last code edit

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS. 1096 tests, 0 failures, 9 skipped |
| `reqstool status local -p docs/reqstool` | 0 requirements and 0 SVCs incomplete |
| `pnpm verify` (tsc, oxlint, vitest jsdom) | pass, `adoption.test.tsx` 8/8 |
| `vitest run --project storybook` | 21 files, 111 tests pass |
| `openspec validate --all --strict` | all pass |
| `mkdocs build --strict` | no warnings |

**Not clean the first time:**
- **Storybook stories.** Run with default parallelism, 10–12 story files (none
  of them touched here) failed to import Storybook's setup module ("Failed to
  fetch dynamically imported module") while every test that loaded passed.
  `--maxWorkers=1` passed 21/21 files. This is load on the machine, not a
  regression; CI runs the suite on its own runner.
- **`ClientTelemetryBoundaryTests`** failed on the first full verify because
  Spring's `DispatcherServlet` registers `/`. That mapping is now in the
  allowed set, since the same test walks the dispatcher's routes. The final
  verify above ran after that fix.

**Not run locally:** `pnpm e2e`. It needs the compose stack. CI's Portal e2e
covers it, and the Adoption page's existing e2e (SVC_GW_OBSERVABILITY_0004)
exercises the page this change adds a section to.

## What the tests prove

- **SVC_GW_OBSERVABILITY_0006.** Real facade clones of two approved snapshots
  give the right holder and delivery counts. A held snapshot's added skill
  appears nowhere. A reclaimed SHA and an unparseable manifest each appear
  under `unresolved` with their holder. `since` drops holders but not
  deliveries. A second read hits the cache.
- **SVC_GW_OBSERVABILITY_0007.** The payload carries `measure` and a
  `statement`, two skills of one snapshot carry identical counts, and no skill
  row has a fetch or invocation field.
- **SVC_GW_OBSERVABILITY_0008.** The write-route, servlet and OTLP-path checks
  run against the live context, and `AdoptionService`'s dependencies are
  pinned.
- **Roles.** `RoleEnforcementTests` classifies `GET /api/v1/adoption/presence`
  as a privileged read and walks it deny-by-default.
