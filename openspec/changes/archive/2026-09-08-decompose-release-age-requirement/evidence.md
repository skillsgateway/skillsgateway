# Evidence: decompose-release-age-requirement

Commit under test: **`7aefe7b2d1d159967a09476ab483c619a8cfca80`** —
`docs(reqstool): decompose GW_APPROVAL_0004 into per-guarantee children`.

All gates below were run from the worktree
(`.claude/worktrees/docs-decompose-composite-snapshot/`) after the last code
edit, in the order the project's `CLAUDE.md` specifies. No source, test or
requirement file changed between any run and this report. Unlike the two
prior changes on this branch (`GW_RELEASE_0003`, `GW_VETTING_0029`), no
fix-up commit was needed here: the annotation and `implementation` gaps found
on those two changes were checked for explicitly on this one before running
the gates (every child confirmed to have a real `@Requirements`/`@SVCs` site,
not just a prose mention, before the first `mvnw` run), and this run confirms
that check held.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  02:32 min
```

Exit code 0, first attempt. `clean` is not optional: the reqstool annotation
processor writes per-source-set files that incremental compilation
truncates, which would report the moved `@Requirements`/`@SVCs` as missing.
`ReleaseAgeGateTests` (3/3), `MinimumReleaseAgeTests` (6/6), and the
frontend's `marketplaces.test.tsx` (9/9, run as part of the same build's
UI-verify phase) all pass, unmoved and unweakened.

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
  13 passed (47.9s)
```

Exit code 0. No retry.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
210/210 complete · 0 incomplete · PASS
```

Last line is `PASS`. 210, up from 205 before this change — the parent plus
five children (`GW_APPROVAL_0004`, `GW_APPROVAL_0004.1` – `GW_APPROVAL_0004.5`)
are all listed complete.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed
```

Exit code 0, including `change/decompose-release-age-requirement`.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.80 seconds
```

Exit code 0.

## Independent id check

Counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 210 dupes []
svcs 210 dupes []
svc->unknown req []
reqs with no svc []
```

210 requirements, 210 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_APPROVAL_NNNN` id and renumbered nothing.
