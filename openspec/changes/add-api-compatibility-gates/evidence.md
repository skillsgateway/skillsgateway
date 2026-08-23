# Evidence — add-api-compatibility-gates

One fresh run of every gate after the last code edit.

**Commit:** `4553f32c2b86d40daf126161763fb6c0e868426a` (`Revert "test: TEMPORARY - remove /api/tokens to prove the co`)

The working tree at this commit is byte-identical to `27d4f2f`, the
implementation commit: `ed8a148` deliberately broke the contract to prove the
gate fires and this commit reverts it (`git diff --quiet 27d4f2f HEAD` is clean).

## `./mvnw clean verify`

```
[INFO] Tests run: 180, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:36 min
```

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (57.6s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
104/104 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 0.92 seconds
```

## The gate, exercised

A gate nobody has seen fail is a gate nobody should trust, so it was run both
ways on this PR (#122).

**Green on this change** — [run 32670691048](https://github.com/skillsgateway/skillsgateway/actions/runs/32670691048), commit `27d4f2f`:

```
Baseline: e1dec0686dd7c3d1e0864a80aad8df59a395c002
running oasdiff breaking... base: baseline-openapi.json, revision: src/main/frontend/openapi.json, fail_on: ERR
BREAKING: false
```

The baseline resolves to the fork point on the base branch, not its tip.

**Red on a removed endpoint** — [run 32670898252](https://github.com/skillsgateway/skillsgateway/actions/runs/32670898252), commit `ed8a148`, which deleted `/api/tokens` from the published contract:

```
##[error]in API GET /api/tokens api path removed without deprecation
##[error]in API POST /api/tokens api path removed without deprecation
BREAKING: true
LABELLED: false
##[error]Breaking contract change without the ⚠️ BREAKING CONTRACT label. See the job summary.
Process completed with exit code 1.
```

The other three rows of the decision table (breaking+labelled+undeclared title;
breaking+labelled+declared; not breaking) were exercised locally against the
workflow's own script, extracted from the YAML:

```
--- case: not breaking                          exit=0
--- case: breaking, no label                    exit=1
--- case: breaking, labelled, title silent      exit=1
--- case: breaking, labelled, feat!:            exit=0
--- case: breaking, labelled, fix(scope)!:      exit=0
```

The staleness check was likewise shown to fail, not merely to pass:
`OpenApiContractTests.theStalenessCheckCanActuallyFail` runs the real assertion
against a drifted document and requires the `AssertionError`.

## Note: one pre-existing flake

The first full `./mvnw clean verify` of this session failed with
`AuditExportTests.ledgerStreamsAsNewlineDelimitedJsonFromACursor` throwing
`ConcurrentModificationException` from `MockHttpServletResponse`. It passed on
re-run, in isolation, and on both subsequent full runs including the one above.

It is unrelated to this change, which touches no audit, export or security code:
the streaming async thread and the filter chain write headers on the same
unsynchronized mock response. The test's own javadoc documents this race and
mitigates it for the `asyncDispatch` window; the failure was at line 55, the
*initial* `perform`, which that mitigation does not cover. Worth its own issue.
