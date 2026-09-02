# Evidence — audit-ledger-vetting-detail

Backend half of #221. Shared podman VM is in use by other agents, so the full
`./mvnw clean verify` (Testcontainers Postgres + Floci) and the two frontend
suites were **deferred** to the serial pre-merge run, per the task's resource
note. The vetting/audit slice — the surface this change touches — was run in full,
plus the OpenAPI contract and reqstool traceability for the two new requirements.
Container-backed tests were pointed at the podman socket via
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (known local-run requirement) with Ryuk
disabled.

## Gates run

### Targeted Java slice (`./mvnw -o clean test -Dtest=…`)

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.VettingTests
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.NativeEnumColumnTests
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.MachineLedgerTests
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.LicenseTests
BUILD SUCCESS
```

`VettingTests` includes the two new SVC tests (`SVC_GW_0142`,
`SVC_GW_0143`) and the amended `SVC_GW_0043` ledger test.
`MachineLedgerTests` (the `GW_0128` actor-typing suite) still passes, confirming
the vetting actor addition did not disturb machine/human classification.

### OpenAPI contract

```
Tests run: 1, ... OpenApiDocsTests   — regenerated target/openapi.json
Tests run: 4, ... OpenApiContractTests — BUILD SUCCESS (committed openapi.json matches)
```

Only change to the served document: the `VerdictView.detail` schema description.
`src/main/frontend/openapi.json` updated; `src/api/types.gen.ts` updated by hand
for that one JSDoc line because the local `openapi-typescript` (7.13.0) is
incompatible with the installed TypeScript (7.0.2) and crashes — CI regenerates
it.

### reqstool traceability (the two new requirements)

```
GW_0142   skills-gateway     (implemented + tested — no "not implemented" suffix)
GW_0143   skills-gateway     (implemented + tested)
8/136 complete · 128 incomplete · FAIL
```

The overall FAIL is expected: only the vetting/audit slice ran, so the other 128
requirements have no fresh test results this run. `GW_0142` and `GW_0143` trace
cleanly to their implementing annotations and passing SVC tests. Full reqstool
PASS follows the serial `./mvnw clean verify`.

### openspec

```
openspec validate audit-ledger-vetting-detail --strict   → valid
openspec validate --all --strict                         → 27 passed, 0 failed
```

## Deferred (run serially pre-merge)

- `./mvnw clean verify` (full Java suite + Spotless/Checkstyle + UI jsdom gate + jar)
- `(cd src/main/frontend && pnpm test:stories)` — frontend untouched
- `(cd src/main/frontend && pnpm e2e)` — frontend untouched
- `reqstool status local -p docs/reqstool` — must end PASS after the full verify
- `mkdocs build --strict`

## Commit

Head at evidence authoring: see the branch tip `fix/audit-ledger-vetting-detail`.
