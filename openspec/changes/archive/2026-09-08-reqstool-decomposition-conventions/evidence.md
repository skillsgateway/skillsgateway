# Evidence: reqstool-decomposition-conventions

Commit under test: **`1a9ba67a0e1c4161a503c195b4fd9ed2dd7fa030`** —
`docs(reqstool): decompose GW_INGEST_0026 into dot-notation child requirements`.

All gates below were run from the worktree after the last code edit. No source,
test or requirement file changed between the run and this report.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
DOCKER_HOST=unix:///var/run/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

```
[INFO] Results:
[INFO]
[INFO] Tests run: 586, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

Exit code 0. `clean` is not optional here: the reqstool annotation processor
writes per-source-set files that incremental compilation truncates, and a
truncated file would report the moved `@Requirements` / `@SVCs` as missing.

The two test classes this change touches:

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.ingestion.ResolutionBudgetTests
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.ExternalSourceResolutionTests
```

### Retries — recorded, because a genuine flake looks identical from here

This run was the **sixth** attempt. The five before it failed on container
startup, not on assertions:

| Attempt | Result | Errors | Assertion failures |
| ---: | --- | ---: | ---: |
| 1 | BUILD FAILURE | 31 | 0 |
| 2 | BUILD FAILURE | 37 | 0 |
| 3 | BUILD FAILURE | 25 | 0 |
| 4 | BUILD FAILURE | 53 | 0 |
| 5 | BUILD FAILURE | 8 | 0 |
| **6** | **BUILD SUCCESS** | **0** | **0** |

Every failure across attempts 1–5 was `Failed to load ApplicationContext`, whose
root cause was one of:

```
Caused by: com.github.dockerjava.api.exception.InternalServerErrorException:
  Status 500: {"cause":"something went wrong with the request: \"proxy already running\\n\""}
Caused by: ...NoHttpResponseException: localhost:2375 failed to respond
Caused by: org.testcontainers.containers.ContainerLaunchException:
  Container startup failed for image floci/floci:1.6.0 / postgres:18.4
```

That is podman's rootless port-forwarding proxy colliding when several Maven
processes start dev-service containers concurrently — up to 68 containers were
running on one 8 GiB podman machine while several agents shared it. Three
observations distinguish it from a real regression:

- **`Failures: 0` in every attempt.** Not one assertion failed at any point; all
  counts are `Errors`, raised before a test body ran.
- **The failing set was disjoint between runs** — `RevetEnforceTests` and
  `DevAuthTests` in attempt 1, `ClaimRoleMappingTests` and `MachineLedgerTests`
  in attempt 2, `EstateReconciliationTests` in attempt 3, `LicensePolicyTests` in
  attempt 5 — none of which this change touches, and no class failed twice.
- **`ResolutionBudgetTests` passed 12/12 in all six attempts**, and
  `ExternalSourceResolutionTests` passed 19/19 in attempts 2, 3, 5 and 6.

Attempt 6 ran while holding a shared gate mutex that serialised the concurrent
runs, and passed clean.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

Exit code 0. No retry.

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (41.9s)
normalized classnames in .../src/main/frontend/test-results/playwright-junit.xml
```

Exit code 0. No retry. Run before the reqstool gate in the same working tree, as
reqstool reads `src/main/frontend/test-results/`.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
173/173 complete · 0 incomplete · PASS
```

Last line is `PASS`. 173, up from 165 before this change — the eight children are
the difference, and all eight are listed complete:

```
  GW_INGEST_0026             skills-gateway
  GW_INGEST_0026.1           skills-gateway
  GW_INGEST_0026.2           skills-gateway
  GW_INGEST_0026.3           skills-gateway
  GW_INGEST_0026.4           skills-gateway
  GW_INGEST_0026.5           skills-gateway
  GW_INGEST_0026.6           skills-gateway
  GW_INGEST_0026.7           skills-gateway
  GW_INGEST_0026.8           skills-gateway
```

This is the load-bearing result of the change: reqstool 0.12.1 accepts a dot in a
requirement id and in an SVC id, resolves `references.requirement_ids`, and maps
`@SVCs({"SVC_GW_INGEST_0026.4"})` to its JUnit test case with no tooling or schema
change.

## 5. `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

Exit code 0.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 2.12 seconds
```

Exit code 0.

## Independent id check

Beyond the gates, because several branches append ids to the same two files
concurrently:

```
reqs 173 dupes []
svcs 173 dupes []
svc->unknown req []
reqs with no svc []
```

173 requirements, 173 SVCs, no duplicate id in either file, no SVC pointing at a
requirement that does not exist, and no requirement without an SVC. This change
minted no top-level `GW_NNNN` id and renumbered nothing.
