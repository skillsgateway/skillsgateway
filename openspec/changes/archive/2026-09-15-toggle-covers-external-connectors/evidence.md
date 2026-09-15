# Evidence: toggle-covers-external-connectors

Commit under test: **`b6c8d297face08cf35575d63dad0dc6506c276e0`** —
`docs(approval): the no-blanket-override sentence predates GW_VETTING_0028`,
the last commit before this report. Every gate below ran from the worktree
`.claude/worktrees/agent-ab73be0a1612ff04e/` **after** the final source,
requirement and documentation edit; the four commits that followed only staged
content the gates had already run against, so the tested tree and the committed
tree are identical.

## 0. The proof, before the words changed

The change is a documentation/contract correction, so the load-bearing evidence
is that the new test would fail if the code matched the old words. Both runs
are `./mvnw -Dtest=ExternalConnectorRegistrationTests test`.

**As shipped** — the switch's known set is the injected `List<VettingConnector>`:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.998 s -- in dev.skillsgateway.server.ExternalConnectorRegistrationTests
[INFO] BUILD SUCCESS
```

**With the code narrowed to built-ins** — one line added to
`ConnectorToggleService`, `.filter(c -> !(c instanceof ExternalVettingConnector))`,
then reverted:

```
[ERROR] dev.skillsgateway.server.ExternalConnectorRegistrationTests.anExternalConnectorIsSubjectToTheSameSwitchAsABuiltIn -- Time elapsed: 2.187 s <<< ERROR!
org.springframework.web.server.ResponseStatusException: 422 UNPROCESSABLE_ENTITY "unknown connector 'llm-review'; the configured connectors are [prompt-injection, license-scan, secret-scan, skill-conformance]"
[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0
```

So the test fails for the right reason, and the behaviour the requirement now
describes is the behaviour that shipped.

## 1. `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify`

```
[INFO] Tests run: 648, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  08:48 min
[INFO] Finished at: 2026-09-15T15:52:54+02:00
```

Exit code 0. `clean` is not optional — the reqstool annotation processor writes
per-source-set files that incremental compilation truncates. This run includes
`OpenApiContractTests`, which passes only because `src/main/frontend/openapi.json`
was regenerated from `target/openapi.json` before it (see gate 6).

**One retry, disclosed.** The first attempt was killed by its own 570-second
`timeout` wrapper at 125 of 127 test classes, with no failure recorded; the run
above is a fresh, complete `clean verify` afterwards. No leaked Testcontainers
container survived the kill (`docker ps --filter label=org.testcontainers=true`
returned none).

## 2. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (14)
  GW_INGEST_0007      skills-gateway  automated test missing
  ...
  GW_AUTH_0025        skills-gateway  automated test missing
204/218 complete · 14 incomplete · FAIL
```

**This is the expected local shape, not a regression.** All fourteen are
verified by SVCs that live in `src/main/frontend/e2e/portal.spec.ts`, which the
Playwright suite runs and this machine did not (gate 5). CI runs that suite and
closes them. None of the fourteen is `GW_VETTING_0029` or any of its children:
`GW_VETTING_0029.1` and `GW_VETTING_0029.2`, whose text and SVCs this change
edits, are both complete.

## 3. `openspec validate --all --strict`

```
✓ change/toggle-covers-external-connectors
Totals: 32 passed, 0 failed (32 items)
```

Exit code 0.

## 4. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.15 seconds
```

Exit code 0. The two new internal links — the API reference to
`../configuration.md#external-connectors`, and the vetting concept page to
`../reference/api/marketplaces.md#connector-enabledisable` — are checked by
`--strict`.

## 5. Gates not run here, and why

`pnpm test:stories` and `pnpm e2e` bind fixed ports and a shared Docker compose
project, and this machine was running a sibling agent's suite throughout. Both
run as their own CI jobs on every PR (#103), which is where their result for
this branch comes from. Neither could be affected by this change: it touches no
portal component, no story and no page — its only UI-adjacent artifact is the
regenerated `types.gen.ts`, whose diff is five JSDoc description strings, and
`pnpm typecheck` over it passed inside gate 1.

## 6. Regenerated contract artifacts

```
cp target/openapi.json src/main/frontend/openapi.json
(cd src/main/frontend && pnpm gen:api-types)
🚀 openapi.json → src/api/types.gen.ts [131.9ms]
```

Both were regenerated **before** the passing `clean verify`, so gate 1's
`OpenApiContractTests` is the check that the committed document is the one the
gateway serves. The diff is description-only — five strings across the
`ConnectorToggle` schema, the `ToggleRequest` schema and the two toggle
operations. No path, operation id, field, type or status code changed, so the
breaking-change gate has nothing to catch: this is additive wording within the
major.
