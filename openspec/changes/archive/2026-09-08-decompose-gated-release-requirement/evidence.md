# Evidence: decompose-gated-release-requirement

Commit under test: **`614be59ca12ca96bfd372766e80bf536703c3f02`** —
`fix(reqstool): mark GW_RELEASE_0003.5 as configuration-implemented` (the
decomposition commit is `d650235`; this fix-up commit corrects a missing
`implementation: configuration` field on `GW_RELEASE_0003.5` that the first
gate run below caught).

All gates were run from the worktree
(`.claude/worktrees/docs-decompose-composite-snapshot/`) after the last code
edit, in the order the project's `CLAUDE.md` specifies. No source, test or
requirement file changed between the final run of each gate and this report.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## Retries, disclosed

The first two attempts at `./mvnw clean verify` (against commit `d650235`,
before the fix-up) failed for reasons unrelated to the decomposition:

1. **First attempt**: the `floci` Arconia dev service container failed to
   start (`Container startup failed for image floci/floci:1.6.0`, "no
   stdout/stderr logs available"). Root cause: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
   was not set, so the container's bind-mounted docker socket resolved to the
   macOS-side Podman path, which does not exist inside the Podman VM — a known
   environment requirement on this machine (see the project's own
   `skillsgateway-gates-local` operational notes), not a code regression.
2. **Second attempt**, with the override set: a cascade of 235 unrelated test
   errors (`authorizationGrantType cannot be null` and similar Spring context
   failures across dozens of otherwise-passing test classes). Root cause,
   confirmed after investigation: **a second `./mvnw clean verify` process was
   running concurrently in the same worktree** (a duplicate build one of the
   coordinating agents had started), and the two builds raced against the same
   `target/` directory and container ports. Not an assertion failure in any
   test.

After killing the duplicate process and pruning one orphaned `floci` container
left behind by the second attempt, **the third attempt — run solo, with the
correct socket override — succeeded**, with `Tests run: 619, Failures: 0,
Errors: 0, Skipped: 9`. That run also caught a real, if small, mistake: `reqstool
status` reported `GW_RELEASE_0003.5` "not implemented" because the child was
missing the `implementation: configuration` field its siblings all carry. Fixed
in commit `614be59`, and every gate below is the **fresh, fourth, and final**
run after that fix, with no other change in between.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  03:16 min
```

Exit code 0. `clean` is not optional here: the reqstool annotation processor
writes per-source-set files that incremental compilation truncates, and a
truncated file would report the `@SVCs` moves in `PackagingTests` as missing.
This run's UI-verify phase (`pnpm run verify`, part of the same Maven build)
also ran the frontend's own type-check, lint, and unit suite: `Test Files 12
passed (12)`, `Tests 51 passed (51)`.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

Exit code 0. No retry.

## 3. `(cd src/main/frontend && pnpm e2e)`

```
Running 13 tests using 1 worker
  ✓  1..13 [chromium] › e2e/portal.spec.ts …
  13 passed (46.3s)
normalized classnames in .../test-results/playwright-junit.xml
```

Exit code 0. No retry.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
201/201 complete · 0 incomplete · PASS
```

Last line is `PASS`. 201, up from 194 before this change — the parent plus
seven children (`GW_RELEASE_0003`, `GW_RELEASE_0003.1` – `GW_RELEASE_0003.7`)
are all listed complete.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed
```

Exit code 0, including `change/decompose-gated-release-requirement`.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.86 seconds
```

Exit code 0.

## Independent id check

Counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 201 dupes []
svcs 201 dupes []
svc->unknown req []
reqs with no svc []
```

201 requirements, 201 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_RELEASE_NNNN` id and renumbered nothing.
