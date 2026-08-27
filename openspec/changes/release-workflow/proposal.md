## Why

The gateway has no way to cut a release. Nothing in `.github/workflows/` creates a
GitHub Release; `native.yml` carries the standing comment *"Release artifacts, once the
release flow exists"*. Today a tag push only fans out to two workflows that happen to
watch for tags — `docs.yml` publishes that version's docs and moves the `stable` alias,
and `native.yml` pushes a GHCR image tag — with no ordering between them, no approval
before anything becomes public, and no check that the tag is even reachable from `main`.
A failed image build leaves a tag and half a release standing.

That ungated fan-out is the substantive problem. Two smaller ones ride along: release
tags carry a `v` prefix, which nothing in the toolchain wants, and the workflow names
have drifted into three different grammars, with GitHub's built-in
`pages-build-deployment` run sitting among them because Pages still deploys from the
`gh-pages` branch rather than from a workflow.

## What Changes

- A single `release.yml`, dispatched manually, owning the whole release: resolve the
  version, run the gates, hold for approval, tag, publish, verify, promote. Version
  resolution auto-detects from Conventional Commits via git-cliff; a dry-run preview is
  the default; a dropdown cuts a non-stable release candidate; entering a version by
  hand that disagrees with auto-detect requires ticking `force`.
- The release is created as a **prerelease**, verified, and only then promoted to
  latest — so nothing resolving "the latest release" can ever see a release whose
  artifacts are missing or wrong.
- Approval is a real gate: a `stable` GitHub environment with a required reviewer,
  positioned before the irreversible, publicly visible steps rather than before the tag.
- **BREAKING (release process, not the API):** release tags lose the `v` prefix.
  `1.0.0`, never `v1.0.0`. Nisse already accepts both, but the `tags: ['v*']` triggers
  match nothing once tags are unprefixed, so trigger and tag format change together.
- `docs.yml` and `native.yml` lose their `tags:` triggers and gain `workflow_call`, so
  `release.yml` drives them in order, after the gate. Their push-to-`main`, schedule and
  dispatch triggers are untouched — ordinary CI behaviour does not change.
- Workflow names adopt one grammar (noun-phrase subject, since checks render as
  `<Workflow> / <Job>`), and three files are renamed to match their names. Job names are
  deliberately left alone: `protect-main`'s required status checks reference them.
- GitHub Pages moves from `gh-pages`-branch deployment to Actions deployment, so the
  built-in `pages-build-deployment` run disappears and publication happens under the
  named `Docs` workflow. `mike` keeps owning version management on the branch.
- The Helm chart's hand-pinned `version`/`appVersion` (`0.1.0`) are stamped from the
  resolved release version instead of drifting.

## Capabilities

### New Capabilities

None. Release automation is the existing `release-packaging` capability's concern —
distribution of the gateway itself — and splitting the process from the artifacts it
publishes would fragment it.

### Modified Capabilities

- `release-packaging`: gains the gated release flow and the version-tag format, and
  revises the existing publication requirement whose trigger contract this change
  changes.
  - **GW_0108** (new) — gated release automation: dry-run preview, version resolution
    from Conventional Commits, the release-candidate path, the approval gate, and the
    prerelease → verify → promote ordering.
  - **GW_0109** (new) — release version tag format: unprefixed three-part semver, the
    tag as the single source of the released version, and releases cut only from a ref
    reachable from `main`.
  - **GW_0072** (revised) — its description currently binds every publish step to
    `push` events, which is precisely what this change replaces. Revised to bind
    publication to a `main` push or the gated release workflow, and to forbid it from a
    schedule or a bare dispatch. `SVC_GW_0072`'s assertions get *stricter*, not looser:
    every assertion dropped is replaced by a stronger one proving the release path is
    gated and the tag unprefixed. No existing SVC coverage is weakened.

## Impact

**Requirements** — `docs/reqstool/requirements.yml` (GW_0108, GW_0109 added; GW_0072
revised with a revision bump) and `docs/reqstool/software_verification_cases.yml`
(SVC_GW_0108, SVC_GW_0109 added; SVC_GW_0072 strengthened).

**Tests** — `src/test/java/dev/skillsgateway/server/PackagingTests.java` currently
asserts `tags: ['v*']` and `github.event_name == 'push'` against `native.yml`. Both
become false, so `releaseWorkflowCarriesThePublishByDigestContract` is rewritten against
the revised GW_0072, and new cases cover GW_0108 and GW_0109.

**Workflows** — `release.yml` (new); `docs.yml` and `native.yml` (triggers,
`workflow_call`, Pages deployment); `check-semantic-pr.yml` → `pr-title.yml`,
`labeler.yml` → `pr-labels.yml`, `validate-renovate.yml` → `renovate-config.yml`.

**A second repository** — `skillsgateway/.github` must first receive the reusable
release workflows and composite actions this change calls, ported from `reqstool/.github`
(itself the mature form of this pattern, with `reqstool-java-maven-plugin` as the Maven
+ Nisse caller). Ships as its own PR ahead of this one; the `uses:` refs here are pinned
to its merge SHA.

**Repository settings, done by hand** — a `stable` environment with a required reviewer,
and Pages switched to `build_type: workflow`. Both are outside the repo. GitHub
auto-creates a named environment with *no* protection rules on first use, so an
unconfigured `stable` means the gate silently passes; the release documentation must say
so.

**Other** — `cliff.toml` (new, repo root, so the release path never fetches a config at
runtime); `helm/skills-gateway/Chart.yaml`; `docs/manual/reference/container-image.md`
(documents `v1.2.0`); `docs/manual/guides/releasing.md` (new) and its `mkdocs.yml` nav
entry.

**Not affected** — the REST API, the git facade, and every trust boundary. This change
touches delivery only. Requirement IDs start at GW_0108 because GW_0096–GW_0107 are
already claimed by in-flight work.
