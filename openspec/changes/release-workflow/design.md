## Context

Release delivery today is an accident of two `tags:` triggers. `docs.yml` and
`native.yml` each independently watch for `v*` tags; pushing one starts both at once,
in no defined order, with nothing in between. There is no workflow that creates a
GitHub Release, so the tag, the docs and the image are the entire release — and if the
native build fails after the docs publish, the site advertises a version that has no
image. `native.yml:127` has carried the note *"Release artifacts, once the release flow
exists"* since the image publication change landed.

Constraints that shape the design:

- **Nisse derives the Maven version from git** (ADR 0002); nothing hand-edits a version.
  Its tag regex is `refs/tags/v?((\d+\.\d+\.\d+)(.*))` — the prefix is optional, so the
  tag can be unprefixed, but a `tags: ['v*']` trigger then matches nothing.
- **`GW_0072` already specifies image publication** and binds every publish step to
  `push` events. This change replaces that binding, so the requirement is revised rather
  than worked around; `PackagingTests` asserts the current text and must be rewritten.
- **`protect-main`'s required status checks are job names**, not workflow names:
  `Build & gates`, `Portal e2e`, `Traceability & spec gates`, `Documentation (strict)`,
  `semantic-pr / Validate PR title`. Renaming a workflow's `name:` is free; renaming a
  job silently un-requires a check.
- **The repo is public**, so environment protection rules are free.

## Goals / Non-Goals

**Goals:**

- One dispatched workflow owning the release end to end, with a dry-run preview as the
  default and no durable side effect until a human approves.
- A release that is never publicly "latest" until its artifacts exist and have been
  checked.
- Unprefixed version tags, consistently, in triggers, docs and tests.
- Publication (`docs.yml`, `native.yml`) driven by the release workflow rather than
  racing it.
- One naming grammar across workflows, and no built-in `pages-build-deployment` run.

**Non-Goals:**

- Publishing to Maven Central. The gateway ships as a container image and a Helm chart,
  not as a library.
- Automating the release *decision* — cutting a release stays a deliberate human act.
- Changing ordinary CI. Pull-request and push-to-`main` behaviour is untouched.
- Signing beyond the existing SBOM attestation.
- Automating the two repository settings (`stable` environment, Pages build type).
  Both are outside the repo and done by hand.

## Decisions

### Port the reqstool release commons into `skillsgateway/.github`

`reqstool/.github` already carries this pattern in mature form —
`common-release-{prepare,tag,assets,promote}.yml` plus `check-version`,
`check-release-branch`, `setup-git-cliff`, `setup-cliff-config` and `resolve-version`
composite actions — and `reqstool/reqstool-java-maven-plugin/.github/workflows/release.yml`
is a Maven + Nisse caller, the same shape this repo needs. Its `cliff.toml` maps 1:1 onto
this repo's allowed commit types (`feat fix refactor chore security revert test docs perf
style ci build`, per `skillsgateway/.github`'s `common-check-semantic-pr.yml`) and already
pins `tag_pattern = "^[0-9]+\.[0-9]+\.[0-9]+$"` — unprefixed, which is the format this
change adopts.

*Alternative — call `reqstool/.github` cross-org, SHA-pinned.* Zero duplication and
already battle-tested, but it makes this project's release path depend on another
organisation's repository. For a gateway whose entire thesis is that upstream git
dependencies must be quarantined and pinned before use, borrowing another org's release
workflow is the wrong posture even when technically sound.

*Alternative — one self-contained `release.yml`.* Everything visible in one file and one
PR, no second repository. Rejected because the org already establishes the commons pattern
for `common-check-semantic-pr.yml` and `common-validate-renovate.yml`; a third pattern in
the same org is the inconsistency this change is otherwise removing.

The ported `setup-cliff-config` has its org-default fallback retargeted at
`skillsgateway/.github`. This repo also gets its **own** `cliff.toml` at the root, so that
fallback never fires: the release path resolves its bump rules from the repo, not from a
network fetch at runtime.

### The gate sits on publication, not on the tag

`prepare → checks → [approve] → tag + prerelease → publish → verify → promote`.

A tag and a prerelease are both cheap to undo and invisible to anything resolving
"latest", so gating before them buys nothing while costing the reviewer the very evidence
that makes approval meaningful. Placing the gate after `prepare` and `checks` means the
reviewer sees the resolved version, the previous tag, the rendered release notes and a
green build before deciding. What sits behind the gate is the irreversible, publicly
visible half: the GHCR push, the `stable` docs alias, and promotion to latest.

Note: `reqstool-java-maven-plugin`'s caller comments claim the gate is on its `tag` job.
It is not — `common-release-tag.yml` declares no `environment:`; the gate is on the
publish job. That comment must not be carried across.

### The release is created as a prerelease, then promoted

A prerelease is publicly readable at `/releases/tags/<tag>` while excluded from
`/releases/latest`. That is exactly what a verification step needs: it can fetch the
published assets over the same unauthenticated path a user takes, and prove the bytes
that landed are the bytes that were built. A **draft** hides the release from
`/releases/latest` too, but its assets 404 without an authenticated token, so the verify
step could not exercise the real download path. Promotion is then a single API call
against a release that already has its artifacts — there is no window in which "latest"
points at an incomplete release.

### `docs.yml` and `native.yml` become callable, and lose their tag triggers

Adding `workflow_call` and deleting `tags:` is what converts two independent racers into
two ordered steps. Their `push: branches: [main]`, `schedule` and `workflow_dispatch`
triggers stay, so nightly native builds and rolling `dev` docs are unaffected. The
version arrives as an input rather than being parsed from `github.ref_name`, which also
retires `docs.yml`'s `${GITHUB_REF_NAME#v}` prefix strip.

This is what forces the `GW_0072` revision: "publish only from push events" was the old
mechanism for ensuring a published tag always traces to a reviewed commit. The new
mechanism is stronger — publication happens only from a `main` push or from behind the
release workflow's approval gate, and never from a schedule or a bare dispatch — but it
is a different sentence, so the requirement and its SVC are revised together and
`SVC_GW_0072` gains assertions rather than losing them.

### Pages moves to Actions deployment, `mike` keeps the branch

`mike` stays the version manager: it still commits the built site to `gh-pages`. The
workflow then uploads that branch as a Pages artifact and calls `actions/deploy-pages`,
and the repository switches to `build_type: workflow`. This keeps `mike`'s version
aliasing (`dev`, `stable`, `set-default`) exactly as it is while removing the built-in
`pages-build-deployment` run, so deployment appears under the named `Docs` workflow.
Requires `pages: write` and `id-token: write`.

### Naming grammar: noun-phrase subject

Checks render as `<Workflow> / <Job>`, so the workflow name is the subject and the job
name the predicate. `Semantic PR → PR title`, `Label PRs → PR labels`,
`Validate Renovate config → Renovate config`, with files renamed to match. `CI`, `Docs`,
`Docs preview` and `Native image` already fit. Job names are untouched — see the
`protect-main` constraint above.

## Risks / Trade-offs

- **An unconfigured `stable` environment silently disables the gate.** GitHub
  auto-creates a named environment on first use with no protection rules, so the job
  runs straight through and the release looks approved when nobody approved it. →
  Configure `stable` with a required reviewer *before* the first non-dry-run release, and
  say so explicitly in `docs/manual/guides/releasing.md`. This is the single most
  important operational note in the change.
- **Two repositories must land in order.** The `uses:` refs here resolve nothing until
  `skillsgateway/.github` carries the commons. → Ship the commons PR first and pin the
  `uses:` refs to its merge SHA; this PR is unmergeable, not subtly broken, until then.
- **`native.yml` conflicts with PR #124**, which is SHA-pinning `setup-graalvm` and
  `docker/login-action` on adjacent lines. → Small and mechanical; whichever lands second
  resolves it. Actions touched here are SHA-pinned in the same style, so the two agree.
- **git-cliff is a new build-time dependency on the release path.** → Installed as a
  version- and SHA-256-pinned binary by `setup-git-cliff`, not via a floating action, and
  the config is read from the repo rather than fetched.
- **Auto-detection can resolve to a version nobody wants** when the range holds only
  skipped types (`ci:`, `build:`). → `prepare` fails with a message naming that cause
  rather than reporting "tag already exists", and `force` plus an explicit version is the
  deliberate override.
- **Losing the `tags:` triggers means a hand-pushed tag now publishes nothing.** That is
  the intent — publication must go through the gate — but it is a behaviour change for
  anyone used to tagging by hand. → Documented in the releasing guide.

## Migration Plan

1. **`skillsgateway/.github` PR**: port the four reusable workflows, five composite
   actions and `cliff.toml`; retarget the `setup-cliff-config` fallback. Merge, note SHA.
2. **This PR**: `cliff.toml`, `release.yml` pinned to that SHA, the `docs.yml` /
   `native.yml` conversion, the renames, the reqstool changes, `PackagingTests`, the
   Chart stamping, and the docs.
3. **By hand, before the first real release**: create the `stable` environment with a
   required reviewer; set Pages to `build_type: workflow`.
4. **Validate with a dry run** on the branch — `prepare` only, nothing durable.

**Rollback**: restoring the `tags:` triggers on `docs.yml` and `native.yml` returns the
old behaviour; `release.yml` is dispatch-only, so it cannot fire on its own. No published
release needs unwinding because nothing is promoted until verification passes.

## Open Questions

- Should the GHCR image also carry a `stable` moving tag alongside its version tag? The
  chart pins by digest, so nothing needs it today; deferred rather than decided.
- `Breaking change detection` (from the in-flight `feat/api-compatibility-gates`) is not
  in `protect-main`'s required checks, so that gate can be bypassed by merging a red PR.
  Out of scope here — it is a ruleset change, not a workflow change — but it should not
  stay unnoticed.
