# Evidence — release-workflow

One fresh run of every gate after the last code edit.

**Commit:** `0970eac515cdd7db73a893c3cec14181c714aae1` (`docs(release): record the PR link on task 9.7`)

Re-run after rebasing onto `2bda5f7` — PR #118 (`feat(roles)`) merged to `main`
mid-change, conflicting on `requirements.yml`, `software_verification_cases.yml`
and `mkdocs.yml`. All three were append-at-end conflicts. The reqstool files
were rebuilt from `main` verbatim plus this branch's own blocks rather than
hand-merged, after a first attempt at resolving them dropped `GW_0100`'s
`revision` field; both files were then re-validated (99 requirements, 99 SVCs,
no duplicates, every `requirement_ids` resolving, every block carrying a
`revision`) and the diff against `main` confirmed to add exactly `GW_0108`,
`GW_0109`, `SVC_GW_0108`, `SVC_GW_0109` plus the `GW_0072`/`SVC_GW_0072`
revision.

`DOCKER_HOST` was exported from `podman machine inspect` for the runs that need a
container runtime; the value in the ambient shell points at a socket that does
not exist on this machine, which fails ~113 tests for reasons unrelated to the
change.

## `./mvnw clean verify`

```
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
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
  12 passed (26.2s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
99/99 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

## `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: /Users/r04755/dev/clones/other/github/skillsgateway/site
INFO    -  Documentation built in 0.59 seconds
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

## Not verified here, and why

- **The release workflow has never run.** `release.yml` calls reusable workflows
  from `skillsgateway/.github`, which do not exist on that repository's `main`
  yet — they are [skillsgateway/.github#1](https://github.com/skillsgateway/.github/pull/1).
  Until it merges, dispatching this workflow fails at reference resolution. The
  dry-run and negative-path exercises in tasks 9.2–9.4 are therefore still open.
- **The `uses:` refs are pinned to that PR's branch head**
  (`50455721fa920c7609e8686abaac71df550e6a06`), not to a merge SHA. Immutable and
  valid, but it must be re-pinned once the PR squash-merges — four occurrences.
- **The two repository settings are not made**: the `stable` environment with a
  required reviewer, and Pages set to `build_type: workflow`. Neither lives in
  this repository. Until the Pages setting changes, `docs.yml`'s `deploy` job
  fails, so that setting has to be made before this merges rather than after.
