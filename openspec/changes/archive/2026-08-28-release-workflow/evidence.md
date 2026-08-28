# Evidence — release-workflow

One fresh run of every gate after the last code edit.

**Commit:** `1776ce5` (`feat(release): gate the release behind an approval and drop the v tag prefix`)

Named as the implementation commit rather than this report's own, which cannot
contain its own hash. Every file this change touches outside
`openspec/changes/release-workflow/` is at its final state as of `6390664`; the
commits after it carry only this report and the task list.

Re-run after rebasing onto `aaab273` — PRs #118, #119, #120, #122 and #124 each
merged to `main` while this change was open, conflicting every time on
`requirements.yml` and `software_verification_cases.yml` — plus `mkdocs.yml` on
the first rebase and `.github/workflows/native.yml` on the last. The reqstool and
nav conflicts were all append-at-end. The `native.yml` one was real: #124
SHA-pinned `docker/login-action` on the same line this change re-gates, and the
resolution keeps both — main's pin and this change's condition.

Both times the reqstool files were rebuilt from `main` verbatim plus this
branch's own blocks rather than hand-merged — a first attempt at resolving them
by hand dropped `GW_0100`'s `revision` field, which is why the rebuild is
scripted. After each rebuild both files were re-validated (106 requirements, 106
SVCs at the final state; no duplicates, every `requirement_ids` resolving, every
block carrying a `revision`) and the diff against `main` confirmed to add
exactly `GW_0108`, `GW_0109`, `SVC_GW_0108`, `SVC_GW_0109` plus the
`GW_0072`/`SVC_GW_0072` revision.

`DOCKER_HOST` was exported from `podman machine inspect` for the runs that need a
container runtime; the value in the ambient shell points at a socket that does
not exist on this machine, which fails ~113 tests for reasons unrelated to the
change.

## `./mvnw clean verify`

```
[INFO] Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (25.2s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
106/106 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

## `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: /Users/r04755/dev/clones/other/github/skillsgateway/site
INFO    -  Documentation built in 0.63 seconds
```

## `actionlint .github/workflows/*.yml`

Not one of the five gates, but this change is mostly workflows.

```
(no output)
exit=0
```

Clean across all eight workflows. It was **not** clean before this change: the
smoke-test loop in `native.yml` tripped `SC2034` (`i appears unused`) on `main`
as well, and since this change edits that file the loop variable became `_`.

## One intermittent failure, not from this change

The first `mvnw clean verify` of this run failed in
`src/main/frontend/src/pages/marketplace-detail.test.tsx`
(`deleted_snapshot_shows_its_restore_deadline_and_control`). An immediate re-run
of the same tree passed, and the file is not touched by this change — it arrived
with #120. Recorded rather than quietly re-run: it is a real flake in the suite
and someone should chase it.

## The negative runs

`SVC_GW_0072`, `SVC_GW_0108` and `SVC_GW_0109` were each proved to fail against
the contract they replace, before being accepted. Not part of the gate sequence
above — recorded because a workflow assertion that cannot fail is worth nothing.

| Break introduced | Result |
| --- | --- |
| Reintroduce `tags: ['v*']` on `native.yml`'s push trigger | `Tests run: 4, Failures: 2` — `releaseWorkflowCarriesThePublishByDigestContract` and `releaseTagsAreBareSemanticVersionsCutOnlyFromReachableUntaggedCommits` |
| Flip `dry-run`'s default to `false` **and** delete the `stable` environment from the `approve` job | `Tests run: 4, Failures: 1` — `releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing` |

Both breaks were reverted and the suite returned to `Tests run: 4, Failures: 0`.

An earlier draft of these tests grepped the workflow text, and three assertions
passed or failed on `PackagingTests.java`'s own comments rather than on the YAML
they described. They now parse the workflow with SnakeYAML and assert on
structure — triggers, job `needs`, step conditions, the `environment` block —
and the `SVC_GW_0109` text checks strip comment lines first.

## The dry-run exercises (tasks 9.2–9.4), run after the fact

These were open when this report was first written, because they were blocked on
[skillsgateway/.github#1](https://github.com/skillsgateway/.github/pull/1). That
PR had in fact merged; the blocker was stale and carried for a while. Closing it
out found a defect that nothing else could have.

**The first dry run failed, and the release flow could never have worked.**
[Run 33174202824](https://github.com/skillsgateway/skillsgateway/actions/runs/33174202824)
died in `prepare` before resolving a version:

```
Could not get github metadata: HttpClientError(reqwest::Error {
  kind: Status(403, None),
  url: "https://api.github.com/repos/skillsgateway/skillsgateway/commits?per_page=100&page=0" })
##[error]Process completed with exit code 101.
```

`resolve-version` runs `git-cliff --bumped-version`, which needs no GitHub
metadata — but `cliff.toml`'s template reads `commit.remote.pr_number` and
`commit.remote.username`, so git-cliff initialises its GitHub remote and calls
the commits API anyway. Unauthenticated, that is a 403. The `Generate changelog`
step three steps later already passed `secrets.GITHUB_TOKEN` for exactly this
reason; version resolution was simply missed. Fixed as
[skillsgateway/.github#2](https://github.com/skillsgateway/.github/pull/2)
(merged `5303024`) and re-pinned here by
[#142](https://github.com/skillsgateway/skillsgateway/pull/142).

**This is the point of tasks 9.2–9.4.** `PackagingTests` parses `release.yml`
structurally and the structure was never wrong. The workflow had been on `main`
since [#125](https://github.com/skillsgateway/skillsgateway/pull/125) looking
complete, and would have failed on the first real release. Only dispatching it
could surface this.

### 9.2 — dry-run preview

[Run 33175167625](https://github.com/skillsgateway/skillsgateway/actions/runs/33175167625) — success.

```
VERSION:    0.1.0
AUTO:       0.1.0
SOURCE:     auto-detected from Conventional Commits
LATEST:
PRERELEASE: false
GIT_CLIFF_VERSION: 2.13.1
```

Bare version, no `v` prefix. `LATEST` is empty because the repository carries no
tags yet, which is also why auto-detect proposes `0.1.0`.

### 9.3 — negative paths, two of three

| Path | Run | Outcome |
| --- | --- | --- |
| `version` disagrees with auto-detect, `force: false` | [33175266927](https://github.com/skillsgateway/skillsgateway/actions/runs/33175266927) | Refused: `Version '9.9.9' disagrees with the auto-detected '0.1.0'. Re-run with force enabled if that is deliberate.` |
| ref not reachable from `main` | [33175269826](https://github.com/skillsgateway/skillsgateway/actions/runs/33175269826) | Refused: `Releases must come from main, hotfix/*, or release/* (got: 'feat/machine-api-credentials').` |
| already-tagged ref | — | **Not exercised.** See below. |

### 9.4 — release candidate

[Run 33175356076](https://github.com/skillsgateway/skillsgateway/actions/runs/33175356076) — success.

```
VERSION:    0.1.0-rc1
AUTO:       0.1.0
SOURCE:     auto-detected from Conventional Commits, as rc
PRERELEASE: true
```

## Not verified here, and why

- **A release has never actually been cut.** Everything above is `dry-run: true`,
  which is one job: `prepare`. No tag has been pushed, no artifact published, no
  approval requested, nothing promoted. The gate itself — the `stable`
  environment pausing the run — has therefore never been observed pausing
  anything.
- **`prerelease: rc` was not observed *failing to promote*.** A dry run skips
  every job after `prepare`, so promotion cannot be observed either way. What
  9.4 proves is the value that drives the gating: `prepare` outputs
  `prerelease: true`, `promote` passes it through as
  `prerelease: ${{ needs.prepare.outputs.prerelease == 'true' }}` (which leaves a
  candidate a prerelease permanently), and `docs` is separately gated
  `if: needs.prepare.outputs.prerelease != 'true'` so the `stable` alias does not
  move. Wiring verified by inspection, value verified by execution, behaviour
  unverified.
- **The already-tagged-ref refusal was not exercised, by the owner's decision.**
  The repository has no tags at all, so the only way to exercise it is to create
  one. Rather than have a throwaway probe be the first tag the repository ever
  carries, the owner will verify this path when cutting the first release.
- **The repository settings were already in place**, contrary to what this report
  originally assumed, and both are readable through the REST API rather than
  being manual steps taken on trust: `GET /repos/.../pages` returns
  `"build_type": "workflow"`, and `GET /repos/.../environments/stable` returns one
  `required_reviewers` rule plus a protected-branches policy. Note
  `prevent_self_review` is `false`, so the approval gate stops an accidental
  release rather than a unilateral one — reasonable for a single maintainer, and
  recorded as an open question rather than silently changed.
