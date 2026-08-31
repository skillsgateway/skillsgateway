# Cutting a release

A release is a manual, gated act. The **Release** workflow owns the whole
sequence — resolve the version, run the gates, tag, publish, verify, promote —
and nothing about it happens as a side effect of a push.

## Before the first release

Two repository settings must be in place. Neither lives in the repository, and
one of them fails silently if it is missing.

!!! danger "The `stable` environment must exist with a required reviewer"

    The approval step is bound to a GitHub environment named `stable`. GitHub
    creates a missing environment on demand **with no protection rules**, so if
    it has not been configured by hand the approval job runs straight through
    and the gate does nothing at all — no error, no warning.

    Create it under **Settings → Environments**, add a required reviewer, and
    confirm the workflow pauses on a real run. Environment protection rules are
    free on public repositories.

!!! warning "Pages must be served from GitHub Actions"

    Documentation deployment runs in the **Docs** workflow, which needs
    **Settings → Pages → Source** set to *GitHub Actions*. While it is still on
    the legacy `gh-pages` branch source, the deploy job fails.

    `mike` still owns versioning on the `gh-pages` branch; only the serving
    mechanism changed. Doing this also retires the built-in
    `pages-build-deployment` run.

## Versions and tags

**The git tag is the only version.** Nothing in the repository holds a version
string that a human edits — Maven derives it from git state through
[Nisse](https://github.com/maveniverse/nisse), and the Helm chart's `version`
is a placeholder that the release stamps from the tag.

Tags are **bare three-part semantic versions**: `1.2.0`, never `v1.2.0`. The
changelog configuration, the published image tag and the version validator all
expect the bare form.

Releases may only be cut from `main`, `hotfix/*` or `release/*`, and only from
a commit that is reachable from that branch and does not already carry a
release tag.

### The version between tags

On any commit that is not itself tagged, Nisse derives a `-SNAPSHOT` version by
increasing the last release tag, and it reads the size of that increase from
the **Conventional Commits** in `tag..HEAD` — the same source the release
workflow derives from. `.mvn/maven.config` selects it:

```
-Dnisse.source.jgit.versionIncrement=conventionalCommits
-Dnisse.source.jgit.versionIncrement.zeroMajorDemotion=true
```

The highest increase in the range wins. Against a `0.1.0` tag:

| Highest commit in `tag..HEAD` | Version on `main` |
| --- | --- |
| `fix:`, `chore:`, or anything not a Conventional Commit | `0.1.1-N-SNAPSHOT` |
| `feat:` | `0.2.0-N-SNAPSHOT` |
| `feat!:`, or a `BREAKING CHANGE:` footer | `0.2.0-N-SNAPSHOT` |

`N` is the number of commits since the tag. A breaking change lands on the
minor rather than the major because `zeroMajorDemotion` holds the `0.x` line —
which is the same rule the release workflow applies, and the reason reaching
`1.0.0` needs `version` and `force` together.

The consequence worth stating plainly: **every commit message is a vote on the
next version.** A `feat:` in a pull request moves the whole line's minor, and a
`!` moves it as far as the pre-1.0 rule allows.

Until the first release tag exists there is nothing to increase, so the version
stays at the `0.1.0-N-SNAPSHOT` fallback whatever the commits say.

## Running it

**Actions → Release → Run workflow.** The branch you pick in the dropdown is
the branch that gets released; there is deliberately no ref input, so what the
gates verify is always what gets tagged.

| Input | Meaning |
| --- | --- |
| `version` | Leave empty to derive it from the Conventional Commits since the last tag. |
| `prerelease` | `none` for a release; `rc`, `b` or `a` for a candidate. The number is chosen for you (`1.2.0` → `1.2.0-rc1`, then the next). |
| `force` | Required before a hand-entered `version` that disagrees with the derived one is accepted. |
| `dry-run` | **On by default.** Resolves and reports; tags nothing, publishes nothing. |

### Preview first

A dry run is the `prepare` job and nothing else, so it costs one cheap job. It
writes the resolved version, the version it derived, the previous tag and the
rendered release notes to the run summary. Read that before turning `dry-run`
off.

If nothing in the range earns a version bump — only `ci:`/`build:` commits, or
nothing conventional — the run fails saying exactly that, rather than
reporting a tag collision.

### Overriding the version

Passing a `version` that differs from the derived one fails unless `force` is
also set. That pairing is the point: a hand-entered version is accepted only
when someone has deliberately said so. Reaching `1.0.0` from a `0.x` line needs
both, because a breaking change before 1.0 bumps the minor rather than
declaring the API stable by accident.

## What happens, in order

```mermaid
flowchart TD
    prepare[prepare: resolve version and notes] --> checks[checks: the gates that guard main]
    checks --> tag[tag: push the tag, create the prerelease]
    tag --> approve{{approval: stable environment}}
    approve --> image[image: build, smoke-test, push to GHCR]
    approve --> docs[docs: publish the version, move stable]
    approve --> package[package: build the jar and chart from the tag]
    package --> assets[assets: assert the version, attach]
    image --> verify[verify: re-check the published bytes]
    assets --> verify
    verify --> promote[promote: mark it latest]
```

Two properties of that order are deliberate:

**The approval guards the publish, not the tag.** Everything before it is
reversible and invisible — a tag can be deleted, and the release is created as
a *prerelease*, which `/releases/latest` excludes. By the time you are asked to
approve, the gates are green, the version is resolved and the notes are
rendered, so you are approving evidence rather than a version string.

**A release is not "latest" until its bytes have been checked.** `verify`
downloads the published assets over the same unauthenticated path a consumer
takes and re-derives everything from what actually landed — a truncated or
clobbered upload is invisible to the build that produced it. Only then does
`promote` flip the release to latest.

A prerelease rather than a draft, because a draft's assets are not readable
without a token, so there would be nothing for `verify` to download.

## Release candidates

Choosing `rc`, `b` or `a` produces a candidate that is built, published and
verified like any release but **never promoted to latest**, and never moves the
documentation's `stable` alias. Candidates are also invisible to versioning:
the `1.2.0` that ends a candidate cycle is still computed from the last stable
tag, and its notes still span everything since that tag.

## If a release goes wrong

Because nothing gates the tag, abandoning a release part-way leaves a tag and a
prerelease behind. Either delete both, or — usually cleaner — fix forward with
the next patch version. A prerelease that was never promoted is not serving
anyone.

!!! note "Hand-pushing a tag publishes nothing"

    This is a change from earlier behaviour, and it is deliberate. `docs.yml`
    and `native.yml` used to watch for tags, so pushing one published the
    documentation and the container image immediately — unordered, and with no
    approval between the tag and the world. Both are now invoked *by* the
    release workflow instead. A tag pushed by hand is inert.

## What a release produces

| Artifact | Where |
| --- | --- |
| Release notes | The GitHub release, generated from Conventional Commits |
| `skills-gateway-<version>.tgz` | Attached to the release — the Helm chart, stamped with the version |
| `skills-gateway-<version>-cyclonedx.json` | Attached to the release — the CycloneDX SBOM |
| The jar | Attached to the release |
| `ghcr.io/skillsgateway/skillsgateway:<version>` | GHCR, with the SBOM attested against the pushed digest — see [Container image](../reference/container-image.md) |
| Versioned documentation | This site, with `stable` pointing at it |

## Related

- [Container image](../reference/container-image.md) — tags, and why to pin by digest
- [Compatibility](../reference/compatibility.md) — what a major version means for the API contract
