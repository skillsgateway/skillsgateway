# Evidence: operations-readiness

One fresh run of every gate after the last edit. Commit `36715755`.

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | `Tests run: 702, Failures: 0, Errors: 0, Skipped: 9`; `Tests 104 passed (104)`; BUILD SUCCESS |
| `pnpm test:stories` | `Test Files 9 passed (9)`, `Tests 45 passed (45)` — **second attempt**, see below |
| `E2E_GATEWAY_PORT=18500 pnpm e2e` | `20 passed (1.1m)` |
| `reqstool status local -p docs/reqstool` | `251/251 complete · 0 incomplete · PASS` |
| `openspec validate --all --strict` | `31 passed, 0 failed` |
| `mkdocs build --strict` | built clean |

`PackagingTests`: `Tests run: 11, Failures: 0`.

## A defect I introduced, and what caught it

The first full run failed with **437 errors** — every container-backed test, on a class
initializer:

```
Caused by: found duplicate key server
 in 'reader', line 70, column 1
```

`application.yaml` already opened with a `server:` block for the session cookie, and I
added a second one for `shutdown: graceful`. A duplicate top-level key, so the whole
document failed to parse and nothing that needed the context could start.

Worth recording for two reasons. The error surfaced as 437 failures in the object-store
suite with no obvious relation to the change, so the temptation was to suspect the AWS
SDK bump that landed on `main` — and the honest fix was a one-line merge of the two
blocks. Verified afterwards by parsing the file directly rather than by inferring it from
a green run:

```
yaml parses cleanly
shutdown: graceful
health groups: {'liveness': {'include': 'livenessState'}, 'readiness': {'include': 'readinessState,db,gitStorage'}}
```

## The story gate flaked again

First attempt `Tests 13 passed (13)` with an exit code of 1 — 13 of 45, the known
cold-cache failure after `mvnw clean`. Passed complete on re-run, unchanged. Recorded
rather than quietly re-run; it is the third time in this run of PRs.

## What was NOT verified, and it matters here

**`helm lint` and `helm template` were not run — helm is not installed on this
machine.** This change edits the chart more than any other in the run: a new
`strategy` block, a `terminationGracePeriodSeconds` field, two probe paths, and an env
block with a `fail` guard.

What does cover it: `PackagingTests` asserts on the template *text*, and CI runs
`helm lint helm/skills-gateway` in `container-image.yml`. Neither renders the template
with values. **The first CI run is the real check on the template syntax**, and a render
error there is my bug, not a flake.

## What the tests can and cannot prove

The packaging test reads the files rather than a running pod. It can prove the
configuration is not silently absent — which is exactly what went wrong, since both
probes pointing at the aggregate was not a bug in code but a line nobody had written. It
**cannot** prove kubelet behaviour under a `RollingUpdate` on an RWO claim, or that a
drain actually completes an in-flight `upload-pack`. Those need a cluster.
