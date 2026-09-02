# Design — fix-release-tag-after-approval

## What the approver looks at

The old ordering existed for one reason: `approve` is a plain job carrying
`environment:`, and its `environment.url` was wired to `needs.tag.outputs.url`.
GitHub renders that URL in the approval prompt and on the environment, so the
tag had to exist before the approval in order for there to be a link. Two ways
out, and only one of them is reachable from this repository.

**A draft prerelease created before the approval, tagged and published after
it.** This is what #205 suggests, and it is the shape that keeps a
release-page-flavoured link in the prompt. It is not available here. Creating a
GitHub release requires a tag — `gh release create` creates the tag if it is
missing, so a "draft before, tag after" split means the tag job itself has to
split into a draft-create half and a publish half, with the tag push between
them. That job is
`skillsgateway/.github/.github/workflows/common-release-tag.yml`, pinned by SHA,
and out of scope. Faking it by inlining a copy of that job here would trade the
shared, SHA-pinned, twice-validated implementation (`check-release-branch` and
`check-version` run inside it, before its checkout) for a divergent local copy —
a worse defect than the one being fixed.

**The run.** `environment.url` becomes
`${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}`.
Chosen. It needs nothing outside this repository, and it is a better target than
the prerelease was:

| The approver wanted to see | Prerelease page | Run summary |
| --- | --- | --- |
| The version being released | yes | yes, plus the version that was *derived* and the previous tag |
| The rendered release notes | yes | yes — `prepare` writes them there |
| Whether the gates passed | no | yes, `checks` is the job above |
| What it costs if the answer is no | a tag and a release to clean up | nothing |

The prerelease page was never the evidence; it was a rendering of a subset of
the evidence, bought by creating the thing under discussion. The draft variant
would remove the cost but not add anything the run summary lacks, which is why
it does not become a follow-up in the shared repository either — the run URL is
the destination, not a stopgap.

## Why not gate the tag job directly

`environment:` cannot be set on a job that uses `uses:`, and `tag` is a reusable
workflow call. That constraint is why a separate `approve` job exists at all. It
also means the gate cannot be pushed *into* the tag job; it can only be ordered
in front of it, which is what this change does.

## The partial-release report

`verify` was the obvious candidate and is the wrong one. It downloads the
published assets and re-derives everything from the bytes — that claim requires
the assets to exist, and it is a *dependent* of `image` and `assets`, so the
moment either fails `verify` is skipped. A job that reports "the release is
incomplete" must run precisely when the jobs it reports on did not succeed, so
it needs `always()` and its own place at the end of the graph.

`if: always() && needs.tag.result == 'success' && needs.promote.result != 'success'`

- Keyed off `tag`, because the tag is what makes the state recoverable-with-work
  rather than nothing. A dry run and a run rejected at the gate both skip `tag`,
  so neither reports.
- `always()` so a cancelled run reports too. That is the likeliest way to reach
  the state and the way #205 reached it. Best-effort: a second cancellation of
  the same run kills this job as well, and nothing can be done about that from
  inside the run.
- `!= 'success'` rather than `contains(needs.*.result, 'failure')`, so a
  cancellation, a skip cascade and a failure all count. A release candidate
  skips `docs` and still promotes, so it does not trip this.

It reports and never fails: the run it is reporting on has already failed, and a
second red job would add noise, not information.

## Re-running a version, and what it needs from the shared repository

`common-release-tag.yml` refuses a version whose tag already exists:

> `Check tag does not already exist` → `::error::Tag '$VERSION' already exists`

So re-dispatching the workflow for an abandoned version stops in the tag job.
That is correct as a default — a second run against someone else's tag is
exactly the collision the check exists to catch — but it means the *only*
supported recovery is fixing forward, and the guide now says so.

Supporting a genuine re-run would take a change in `skillsgateway/.github`,
written up here rather than guessed at in this repository:

- **File**: `.github/workflows/common-release-tag.yml`, job `tag`.
- **New input**: something like `resume: boolean, default false`.
- **Behaviour when `resume` is true**: if the tag exists *and* points at the
  commit being released, skip the create-and-push step and reuse it; if it
  exists and points elsewhere, keep failing — that is a genuine collision.
  Likewise reuse an existing prerelease for the tag instead of calling
  `gh release create`, and set the `url` output from it.
- **Output**: `url` must be populated on the reuse path too, or every downstream
  consumer of it breaks.
- **Caller side**: a `resume` input on `release.yml`'s dispatch, off by default,
  passed through — and the release guide's "can this version be re-run?" answer
  changes from "no" to "only with `resume`".

Also in that repository, and independent of the above: the header comment of
`common-release-tag.yml` states that "the approval sits on the publish instead",
which is the rationale this change reverses for its only caller. It should say
that ordering is the caller's decision, and that a caller that gates in front of
this job is the reason the "tag does not already exist" re-check inside it
matters — a comment three lines further down already assumes an approval sits
between `prepare` and this job.

## Open questions

1. Whether `image` and `docs` genuinely need to wait on `tag`. Neither checks
   out the tag — `native.yml` and `docs.yml` build the caller's ref and take the
   version as an input — so both *could* run in parallel with tagging. They are
   ordered after it anyway: a GHCR tag or a published docs version for a version
   that has no tag is a worse partial state than a slightly slower release, and
   the parallelism saved is one short job. Only `package` needs the tag
   materially; it checks out `ref: <version>`.
2. Whether the `partial` job should also assert against reality (query GHCR and
   the release) rather than report job results. It reports results deliberately:
   an assertion against reality is a second thing that can be wrong, in a job
   whose whole purpose is to be trustworthy when everything else has failed.
