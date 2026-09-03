## Why

`release.yml` runs `tag` before `approve`, so the only human decision in the
release cannot decide whether the version comes into existence. The first
`0.1.0` run proved it: cancelled at the approval gate, it still left the tag
`0.1.0` and a GitHub prerelease behind with no image, no chart, no SBOM and no
documentation version ([#205](https://github.com/skillsgateway/skillsgateway/issues/205)).

`GW_0108` already requires the approval to be held "before any publicly visible
or irreversible step". A pushed tag is both — `GW_0109` makes the tag the single
source of the released version, and `releasing.md` calls it the only version
there is. So this is the implementation disagreeing with the requirement it was
written for, not a requirement that needs rethinking. What `GW_0108` did not
anticipate is the state a run can still leave between the tag and promotion, and
that nothing reports it.

The ordering was not accidental: `approve`'s `environment.url` was
`needs.tag.outputs.url`, so the tag existed early only to give the reviewer a
prerelease page to look at. That is a link, bought at the price of the gate.

Two smaller things ride along, both surfaced by the same run. `verify` is
structurally incapable of reporting the aborted state — a failed publishing job
skips it — so a partial release ends as a red job list and nothing else. And
`releasing.md` documents no recovery at all: an operator in that state has to
decide unaided between deleting the tag, re-running, and burning the version.

## What Changes

- **The tag moves behind the approval.** Job order becomes
  `prepare → checks → approve → tag → publish → verify → promote`. `checks`
  stays in front of the approval, so the reviewer still approves something
  already green rather than a version string. Nothing before the approval writes
  anything to the repository or the world, so a cancelled or rejected run now
  leaves nothing at all.
- **The approver inspects the run, not a prerelease.** `environment.url` points
  at the run. `prepare` has already written the resolved version, the derived
  version, the previous tag and the rendered release notes to the run summary,
  and `checks` is green above it — strictly more evidence than the prerelease
  page carried, and it costs nothing when the answer is no. The alternative,
  a *draft* prerelease created before the approval and published after it, is
  not available from this repository: the tag job lives in the shared
  `skillsgateway/.github` workflows, which this change does not touch. See
  `design.md`.
- **A partial release reports itself.** A new `partial` job runs when the tag was
  created and promotion was not reached — including when the run was cancelled,
  which is how a release is abandoned — and writes which steps succeeded to the
  run summary, with a warning annotation. `verify` is left alone; it answers a
  different question and cannot answer this one.
- **`releasing.md` gains a recovery section**: what each stopping point leaves
  behind, that re-running a version whose tag exists fails in the tag job, that
  deleting the tag and re-running is unverified, and the fix-forward and
  clean-up paths with their commands and their conditions.

Not breaking: the workflow's inputs, its dispatch-only trigger, the tag format
and the published artifacts are all unchanged. Only the order in which they
happen, and what a failed run says about itself, change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `release-packaging`: `GW_0108` — the approval is named as preceding the tag
  specifically, rather than "any publicly visible or irreversible step" in the
  abstract, and gains the obligation to report the surviving state of a run that
  tagged without promoting. `SVC_GW_0108` gains both assertions.

## Impact

**Workflows**

- `.github/workflows/release.yml` — the reorder, the run-scoped environment URL,
  and the `partial` job.

**Code**

- `src/test/java/dev/skillsgateway/server/PackagingTests.java` — `SVC_GW_0108`
  asserts the new order and the partial report.
- `docs/reqstool/requirements.yml`, `docs/reqstool/software_verification_cases.yml`.

**Documentation**

- `docs/manual/guides/releasing.md` — the order diagram, what the approval
  decides, and the new recovery section.

**Explicitly out of scope**

- **`skillsgateway/.github` is not touched.** Two follow-ups belong there and are
  written up in `design.md`: the stale rationale comment at the top of
  `common-release-tag.yml`, and the absence of any way to re-run a release for a
  version whose tag already exists.
- **The orphaned `0.1.0` tag stays.** Decided on #205; the guide now records the
  consequence for the version arithmetic on `main`.
