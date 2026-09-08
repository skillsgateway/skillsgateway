# Evidence: decompose-connector-toggle-requirement

Commit under test: **`b416d88f91254594649d733296c018f73ab8366d`** —
`fix(reqstool): keep GW_VETTING_0029 annotated on its aggregation site` (the
decomposition commit is `18282af`; this fix-up commit corrects a missing bare
`GW_VETTING_0029` annotation that the first gate run below caught — moving
every annotation to the children left the parent with none).

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

The first `./mvnw clean verify` at commit `18282af` (before the fix-up)
**succeeded** — `Tests run: 619, Failures: 0, Errors: 0, Skipped: 9`,
`BUILD SUCCESS` — but the subsequent `reqstool status local -p docs/reqstool`
reported `GW_VETTING_0029` (the parent) `not implemented`, 204/205 complete.
Decomposing the requirement moved every `@Requirements({"GW_VETTING_0029"})`
site to a child id (`.1`–`.4`) with none left carrying the bare parent id, so
reqstool could no longer find an implementation site for it even though its
SVC's two bracketing tests (`a_disabled_verdict_never_rescues_a_failing_one`,
`the_pre_existing_rules_are_unchanged`) pass. Fixed in commit `b416d88` by
keeping the bare id on `VettingChain.aggregate` alongside its `.3` child,
mirroring how the method already keeps `GW_VETTING_0002` there too. Every gate
below is the **fresh, second, and final** run after that fix, with no other
change in between. No test was added, removed, renamed or weakened.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  02:28 min
```

Exit code 0. `clean` is not optional: the reqstool annotation processor writes
per-source-set files that incremental compilation truncates, which would
report the moved `@Requirements`/`@SVCs` as missing. `ConnectorToggleTests`
(2/2) and `VettingChainDisabledTests` (5/5) both pass, unmoved and
unweakened.

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
  13 passed (47.7s)
```

Exit code 0. No retry.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
205/205 complete · 0 incomplete · PASS
```

Last line is `PASS`. 205, up from 201 before this change — the parent plus
four children (`GW_VETTING_0029`, `GW_VETTING_0029.1` – `GW_VETTING_0029.4`)
are all listed complete.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed
```

Exit code 0, including `change/decompose-connector-toggle-requirement` and
the still-open `change/admin-vetting-override` (whose delta this change also
updated — see the design.md's "OpenSpec quirk" section).

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.81 seconds
```

Exit code 0.

## Independent id check

Counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 205 dupes []
svcs 205 dupes []
svc->unknown req []
reqs with no svc []
```

205 requirements, 205 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_VETTING_NNNN` id and renumbered nothing.
