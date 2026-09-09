# Evidence: clamp-the-resolution-budgets

One fresh run of every gate after the last code edit.

Commit under test: `6024beb` (rebased after #342 and #343 merged and #344 was replayed onto main; gates re-run)
Branch: `feat/clamp-the-resolution-budgets` (on top of `feat/revocation-freshness-is-not-a-knob`)

## Gates

```
./mvnw clean verify                          → BUILD SUCCESS, 647 tests, 0 Checkstyle violations
(cd src/main/frontend && pnpm test:stories)  → 6 tests passed
(cd src/main/frontend && pnpm e2e)           → 13 passed (44.6s)
reqstool status local -p docs/reqstool       → 218/218 complete · 0 incomplete · PASS
openspec validate --all --strict             → 31 passed, 0 failed
mkdocs build --strict                        → built in 1.17s
```

The e2e run used `E2E_GATEWAY_PORT=18081`; 8081 was held by an unrelated process
on this machine. `run-e2e.sh` takes the port from that variable, so nothing about
the suite changed.

## The analysis that replaced the original plan

Measured on this branch, against `target/config-surface.txt`:

- **104 leaves. Zero have an accessor that is never called in `src/main`.** Two
  false positives were checked by hand: `retention.defaults.superseded` (read via
  `supersededCriterionEnabled()` inside the record, which an accessor grep
  excludes along with the rest of the file) and `mirror.sweep-initial-delay` (read
  by Spring through the `@Scheduled` placeholder, which no accessor grep can see).
- **40 leaves are never set** by the chart, `application.yaml`, `compose.e2e.yaml`,
  `.github/` or any test. That list is itself unreliable: it wrongly includes
  `storage.object-store.cache.dir`, which the chart sets through
  `SKILLSGATEWAY_STORAGE_OBJECTSTORE_CACHE_DIR` — relaxed binding collapses
  `object-store` to `OBJECTSTORE`, so a literal search for the property path
  misses it. `PackagingTests` asserts that the chart sets it.

No batch of safe cuts exists, so none was made.

## Mutation results

| Mutation | Test that failed |
| --- | --- |
| The `max-blob-bytes` clamp removed | `ResolutionBudgetCeilingTests.noBoundCanBeRaisedPastWhatTheGatewayWillDefend:134` |
| The clamp applies downward as well as upward | `.aBoundCanStillBeRaisedForAMarketplaceThatOutgrewTheDefault:97`, `.everyBoundCanBeLowered:66`, `.everyCeilingIsAboveItsDefault:161` |
| `MAX_BLOB_CEILING` set below its own default (100MB → 5MB) | `.aBoundCanStillBeRaisedForAMarketplaceThatOutgrewTheDefault:97`, `.everyCeilingIsAboveItsDefault:169`, `.noBoundCanBeRaisedPastWhatTheGatewayWillDefend:134` |

## Two environment findings from this run

- **`./mvnw clean verify` does not validate `requirements.yml` as YAML.** An
  unquoted scalar containing `": "` — added in this change's new requirement —
  passed the whole Maven build, including the reqstool plugin's assembly step, and
  was caught only by the standalone `reqstool status`. Quoting the field fixed it.
  The Maven gate is not a substitute for the reqstool gate.
- **A leaked Testcontainer from an earlier interrupted run failed
  `CatalogTests.publications_and_revocations_reshape_the_catalog_on_their_own`**
  with a `RefTransitions.write` failure inside a catalog rebuild — nothing this
  change touches. It passed alone, and the full suite passed after
  `docker rm -f $(docker ps -q --filter label=org.testcontainers=true)`.

## What the gates do not cover

- **The ceiling values are a judgement, not a derivation.** "An order of magnitude
  above the default" is a defensible rule of thumb and not a measurement of what
  this gateway can actually survive; no test establishes that 2GB of inflated
  content is safe on a given heap. What the tests establish is that the ceilings
  exist, are above the defaults, and are applied per bound.
- **The warning is not asserted.** The tests check the clamped values, not that a
  log line was emitted. A clamp that stopped logging would pass — the operator
  would get the safe value and no explanation, which is the failure mode the log
  exists to prevent.
- **No test drives a clamp through the binder.** The budgets are constructed
  directly. Whether a YAML value above a ceiling reaches the record at all is
  Spring's contract, exercised by the rest of the suite but not by these cases.
- **Nothing checks the ceilings against the ingestion path.** That a resolution
  actually refuses content past a clamped bound is `SVC_GW_INGEST_0026`'s job and
  is unchanged here; these cases stop at the value the record holds.
