## 1. Requirements (reqstool SSOT first)

- [x] 1.1 Add `GW_0108` (gated release automation) to `docs/reqstool/requirements.yml` with `implementation: configuration`, following the `GW_0072` style
- [x] 1.2 Add `GW_0109` (release version tag format — unprefixed three-part semver, tag as the sole source of the released version, releases only from a ref reachable from `main`) to `docs/reqstool/requirements.yml`
- [x] 1.3 Revise `GW_0072`: replace the "publish only from push events" clause with publication only from a `main` push or the gated release workflow, never from a schedule or a bare dispatch; bump its `revision`
- [x] 1.4 Add `SVC_GW_0108` and `SVC_GW_0109` to `docs/reqstool/software_verification_cases.yml` as `verification: automated-test`
- [x] 1.5 Strengthen `SVC_GW_0072`'s description so every assertion this change removes is replaced by a stronger one (release path gated, tag unprefixed) — never a net loss of coverage

## 2. Port the release commons to `skillsgateway/.github` (separate PR, merges first)

- [x] 2.1 Port `common-release-prepare.yml`, `common-release-tag.yml`, `common-release-assets.yml`, `common-release-promote.yml` from `reqstool/.github`
- [x] 2.2 Port the `check-version`, `check-release-branch`, `setup-git-cliff`, `setup-cliff-config`, `resolve-version` composite actions, preserving the `$/` self-reference idiom so actions resolve against the commons repo at the running SHA
- [x] 2.3 Port `.github/cliff.toml` as the org default and retarget `setup-cliff-config`'s fallback URL from `reqstool/.github` to `skillsgateway/.github`
- [x] 2.4 Open, review and merge that PR; record the merge SHA for pinning — **PR [skillsgateway/.github#1](https://github.com/skillsgateway/.github/pull/1) merged** as `9d486e25b782f1ec829c69f93aeeec081182180c`. All four `uses:` refs in `release.yml` re-pinned from the pre-squash branch head `5045572` (reachable, but on no branch) to that merge SHA.

## 3. Release workflow in this repo

- [x] 3.1 Add `cliff.toml` at the repo root so `setup-cliff-config` takes the repo branch and never fetches at runtime; verify its commit types match the org's semantic-PR list and its `tag_pattern` is unprefixed
- [x] 3.2 Add `.github/workflows/release.yml` (`name: Release`) with `workflow_dispatch` inputs `version`, `prerelease` (choice `none|rc|b|a`), `ref`, `force`, `dry-run` (default `true`), and `concurrency: {group: release, cancel-in-progress: false}`
- [x] 3.3 Wire `prepare → checks → [approve] → tag → publish → verify → promote`, pinning every `uses:` to the SHA from 2.4; put the `stable` environment gate on the publish job, not on `tag` (covers `SVC_GW_0108`)
- [x] 3.4 Guard `promote` on "no job failed" rather than "all succeeded", so a release candidate that skips publish jobs does not cascade a skip into every real release

## 4. Convert the publication workflows

- [x] 4.1 `native.yml`: add `workflow_call` with a `version` input, remove `tags: ['v*']`, keep `push: branches: [main]` + `schedule` + `workflow_dispatch`; take the image tag from the input instead of `github.ref_name`; SHA-pin the actions touched, matching PR #124's style (covers revised `SVC_GW_0072`)
- [x] 4.2 `docs.yml`: add `workflow_call` with a `version` input, remove `tags: ['v*']`, delete the `${GITHUB_REF_NAME#v}` strip, and retarget the `startsWith(github.ref, 'refs/tags/v')` condition to the input
- [x] 4.3 `docs.yml`: after `mike` commits to `gh-pages`, upload that branch as a Pages artifact and deploy with `actions/deploy-pages`; add `pages: write` and `id-token: write`
- [x] 4.4 Stamp `helm/skills-gateway/Chart.yaml`'s `version` and `appVersion` from the resolved release version instead of the hand-pinned `0.1.0`
- [x] 4.5 `ci.yml`: add `workflow_call` so `release.yml` can reuse the real gates instead of a drifting copy (not in the original plan — required by 3.3)
- [x] 4.6 `release.yml`: drop the planned `ref` input — `uses: ./…` resolves to the caller's ref, so a `ref` input would tag one commit while `checks`/`image` verified another

## 5. Naming pass

- [x] 5.1 `check-semantic-pr.yml` → `pr-title.yml`, `name: PR title`
- [x] 5.2 `labeler.yml` → `pr-labels.yml`, `name: PR labels`; update the `.github/labeler.yml` header comment that points at the old filename
- [x] 5.3 `validate-renovate.yml` → `renovate-config.yml`, `name: Renovate config`; update its self-referencing `paths:` triggers
- [x] 5.4 Confirm no **job** name changed anywhere: `Build & gates`, `Portal e2e`, `Traceability & spec gates`, `Documentation (strict)` and `semantic-pr / Validate PR title` must still match `protect-main`'s required checks

## 6. Tests

- [x] 6.1 Rewrite `PackagingTests.releaseWorkflowCarriesThePublishByDigestContract` against the revised `GW_0072`, keeping `@SVCs({"SVC_GW_0072"})`: drop the `tags: ['v*']` and bare `github.event_name == 'push'` assertions and replace them with stricter ones proving publication is reachable only from a `main` push or `workflow_call`, and never from `schedule`
- [x] 6.2 Add a test asserting `release.yml`'s gated shape — dispatch-only, `dry-run` defaulting to `true`, the `prerelease` choice options, the `stable` environment on the publish job, and `promote` ordered after `verify` — annotated `@SVCs({"SVC_GW_0108"})`
- [x] 6.3 (each of 6.1–6.3 proved to fail against the old contract before being accepted: reintroducing `tags: ['v*']` fails 6.1+6.3; flipping the `dry-run` default or removing the `stable` gate fails 6.2)
- [x] 6.3 Add a test asserting the tag format contract — no `v`-prefixed tag pattern in any workflow, `cliff.toml`'s `tag_pattern` unprefixed, and the reachable-from-`main` check present — annotated `@SVCs({"SVC_GW_0109"})`
- [x] 6.4 Run `reqstool status local -p docs/reqstool` after `./mvnw clean verify` and confirm it ends `PASS` (annotations are truncated without `clean`)

## 7. Documentation

- [x] 7.1 Add `docs/manual/guides/releasing.md`: cutting a release, the dry-run default, the prerelease dropdown, when `force` is required, the prerelease → verify → promote ordering, and the no-`v` tag rule
- [x] 7.2 State prominently in that guide that the `stable` environment must be created **with a required reviewer** before the first real release — GitHub auto-creates it unprotected, which silently disables the gate
- [x] 7.3 Note in that guide that hand-pushing a tag no longer publishes anything, by design
- [x] 7.4 Fix `docs/manual/reference/container-image.md:20` — `` `<version>` (e.g. `v1.2.0`) `` becomes `1.2.0`
- [x] 7.5 Add the releasing guide to `mkdocs.yml` nav
- [x] 7.6 Update `.claude/skills/documentation/SKILL.md` and `docs/manual/reference/container-image.md`, both of which documented the retired `v*`-tag publishing behaviour (not in the original plan)

## 8. Repository settings (by hand, outside the repo)

- [ ] 8.1 Create the `stable` environment with a required reviewer
- [ ] 8.2 Switch Pages to `build_type: workflow`
- [ ] 8.3 Confirm `https://skillsgateway.github.io/skillsgateway/` still resolves and no `pages-build-deployment` run appears

## 9. Gates and evidence

- [x] 9.1 `actionlint` over every changed workflow
- [ ] 9.2 Dispatch `release.yml` with `dry-run: true` on the branch; confirm the resolved version is bare (`0.2.0`, not `v0.2.0`) and the summary shows version, previous tag and rendered notes — **blocked**: needs skillsgateway/.github#1 merged, or the workflow cannot resolve its `uses:` refs
- [ ] 9.3 Dry-run the negative paths: a `version` disagreeing with auto-detect without `force` must fail; a ref not reachable from `main` must fail; an already-tagged ref must fail — **blocked** on the same
- [ ] 9.4 Dry-run `prerelease: rc` and confirm it resolves to `-rc1` and does not promote — **blocked** on the same
- [x] 9.5 Run all five gates from CLAUDE.md fresh after the last edit; write `openspec/changes/release-workflow/evidence.md` with the commands, pasted result tails and the commit SHA
- [x] 9.6 `openspec validate --all --strict`
- [x] 9.7 Open the PR with an **Evidence** section — **[#125](https://github.com/skillsgateway/skillsgateway/pull/125)**; archive the change with `/opsx:archive` as the final commit (deferred: the change is not implementable-complete until the commons PR merges and the `uses:` refs are re-pinned)
