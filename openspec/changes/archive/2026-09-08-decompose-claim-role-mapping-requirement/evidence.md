# Evidence: decompose-claim-role-mapping-requirement

Commit under test: **`4ded1c617c59aa99ac058237c2dba8e738d6cb30`** —
`docs(reqstool): decompose GW_AUTH_0015 into per-guarantee children`.

All gates below were run from the worktree
(`.claude/worktrees/docs-decompose-composite-snapshot/`) after the last code
edit, in the order the project's `CLAUDE.md` specifies. No source, test or
requirement file changed between any run and this report. As with
`GW_APPROVAL_0004`, every child's real `@Requirements`/`@SVCs` annotation
site (not just a prose mention) was verified before running the gates,
including that the bare parent id still had one real annotation left after
every guarantee moved to a child (`ClaimRoleMapper.validated` keeps it
alongside `.2`) — the check this branch's `GW_RELEASE_0003` and
`GW_VETTING_0029` fix-up commits established as mandatory. No fix-up was
needed here.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  02:33 min
```

Exit code 0, first attempt. `clean` is not optional: the reqstool annotation
processor writes per-source-set files that incremental compilation
truncates, which would report the moved `@Requirements`/`@SVCs` as missing.
`ClaimRoleMapperTests` (7/7), `ClaimRoleMappingTests` (9/9, including the
unrelated `SVC_GW_ESTATE_0003` test on the same fixture) and
`ClaimMappedRoleIsEnforcedTests` (1/1) all pass, unmoved and unweakened.

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
  13 passed (47.2s)
```

Exit code 0, including the modified
`the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim`
test. No retry.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
213/213 complete · 0 incomplete · PASS
```

Last line is `PASS`. 213, up from 210 before this change — the parent plus
three children (`GW_AUTH_0015`, `GW_AUTH_0015.1` – `GW_AUTH_0015.3`) are all
listed complete.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed
```

Exit code 0, including `change/decompose-claim-role-mapping-requirement`.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.83 seconds
```

Exit code 0.

## Independent id check

Counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 213 dupes []
svcs 213 dupes []
svc->unknown req []
reqs with no svc []
```

213 requirements, 213 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_AUTH_NNNN` id and renumbered nothing.

This is the last of the five candidates issue #299 left unevaluated after
`GW_INGEST_0026`, `GW_INGEST_0030` and `GW_INGEST_0024` — every one of
`GW_RELEASE_0003`, `GW_VETTING_0029`, `GW_APPROVAL_0004`, `GW_VETTING_0020`
and `GW_AUTH_0015` now has a verdict (four decomposed, one —
`GW_VETTING_0020` — judged monolithic).
