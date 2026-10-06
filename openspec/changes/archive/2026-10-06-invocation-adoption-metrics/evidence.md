# Evidence: invocation-adoption-metrics

The design PR shipped ADR 0016 and this proposal with no code. Its evidence was
CI on that branch. This file records the implementing PR for issue #85,
including the review follow-ups in section 9 of `tasks.md`.

**Commit under test:** `518b9ca38b8599f4ac158e63a12ac990efb2365f`. The Java
and frontend test runs below ran on its parent `88b7a160`, which has the same
code; `518b9ca` only restores a `revision` line in
`software_verification_cases.yml`, and reqstool, OpenSpec and mkdocs ran after
it.

## Gates, one fresh run after the last code edit

| Gate | Result |
| --- | --- |
| `./mvnw clean verify`, Java tests | 1098 tests, 0 failures, 9 skipped |
| `./mvnw clean verify -DskipTests` | BUILD SUCCESS: 0 Checkstyle violations, SBOM, packaged jar |
| `pnpm verify` steps (tsc, oxlint, `pnpm test`, reqstool tags and merge) | pass, 30 files, 230 tests |
| `pnpm test:stories` | 21 files, 111 tests pass |
| `pnpm e2e` | 27 passed |
| `reqstool status local -p docs/reqstool` | 343/343 complete, PASS |
| `openspec validate --all --strict` | 30 passed, 0 failed |
| `mkdocs build --strict` | no warnings |

**Why `clean verify` is in two rows.** Another session's build kept the
machine at load average 10–20. One `clean verify` failed in its frontend step:
`marketplace-detail.test.tsx` and `webhooks.test.tsx` (neither touched here)
timed out under parallel vitest. Both passed alone, and the full unit suite
passed afterwards at default parallelism. The next `clean verify` finished all
1098 Java tests and was then cut off by the 10-minute call limit before
packaging. The packaging half ran as `clean verify -DskipTests` with those test
reports kept. CI runs the whole gate in one job.

## What the tests prove

- **SVC_GW_OBSERVABILITY_0006.** Real facade clones of two approved snapshots
  give the right holder and delivery counts. A held snapshot's added skill
  appears nowhere. A reclaimed SHA and an unparseable manifest each appear
  under `unresolved` with their holder. `since` drops holders but not
  deliveries. On a second read, every resolvable SHA comes from the cache and
  only the unresolvable ones miss. A manifest that repeats a plugin name
  counts its SHA once.
- **SVC_GW_OBSERVABILITY_0007.** The payload carries `measure` and a
  `statement`, two skills of one snapshot carry identical counts, and no skill
  row has a fetch or invocation field.
- **SVC_GW_OBSERVABILITY_0008.** The write-route, servlet and OTLP-path checks
  run against the live context, and `AdoptionService`'s dependencies are
  pinned. An OTLP path must answer exactly 404.
- **SVC_GW_RETENTION_0012.** A trim below every sink's position removes an
  identity's older pack send, an anonymous one and a ref advertisement. It
  keeps each identity's latest pack send, and the latest-fetch read still
  names both identities.
- **Roles.** `RoleEnforcementTests` classifies `GET /api/v1/adoption/presence`
  as a privileged read and walks it deny-by-default.

**Shown to fail without the fix:** with the trim's keep-latest clause removed,
`LedgerTrimTests` fails on "what ada holds". With the repeated-SHA guard
removed, the repeated-plugin test fails. With a mapped POST route added to the
OTLP paths, the boundary test fails with 403 instead of 404.

**Portal.** `/impeccable audit` on the Adoption page found one P1: a long
`SKILL.md` path overflowed a `nowrap` cell. It is fixed, and was checked at
1280 and 390 px in light and dark. The detector reports nothing.
