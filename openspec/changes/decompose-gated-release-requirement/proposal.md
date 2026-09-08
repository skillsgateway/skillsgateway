# Proposal: decompose-gated-release-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026`, PR #333 repeated for
`GW_INGEST_0030`, and PR #334 repeated for `GW_INGEST_0024`. `GW_RELEASE_0003`
— *Gated release automation* — is one of the five candidates those changes left
unevaluated, and this change does exactly that one requirement and nothing
else.

`GW_RELEASE_0003`'s single description enumerated seven distinct,
independently verifiable guarantees behind one id and one SVC:

1. a release is dispatched manually and previews by default;
2. a hand-entered version needs an explicit override to disagree with the
   derived one;
3. a release-candidate mode is offered alongside a plain release;
4. the tag and everything that publishes from it wait on the main-branch
   gates and a required reviewer's approval;
5. the release is created as a prerelease;
6. promotion to latest waits on verification of the published artifacts;
7. a run that tags without promoting reports what survived it.

These have different audiences and different failure modes. A release
engineer relying on the manual-dispatch guard cares about 1; a reviewer
relying on the approval gate cares about 4; an operator debugging a stuck
release cares about 7. Each can regress independently of the rest — a
workflow edit could drop the `force` requirement without touching the
approval gate at all — and one pass/fail verdict across all seven could not
say which one broke.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_RELEASE_0003` keeps its id and gains seven children**,
  `GW_RELEASE_0003.1` through `GW_RELEASE_0003.7`, each linked to it by
  `references.requirement_ids`.
- **The parent's own text becomes the cross-cutting claim that is true across
  the whole set and is not any single child's**: no publicly-visible or
  irreversible release step runs outside one continuous chain of evidence and
  human decision — the children each state one link of that chain. That claim
  previously lived only in the requirement's rationale.
- **One parent SVC and seven child SVCs**, all annotated on the same single
  test method — `PackagingTests.
  releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing` —
  because the requirement is verified structurally, against the workflow
  definition, by one test with several independent assertions rather than by
  several separate test methods. `SVC_GW_RELEASE_0003` is retargeted to the
  cross-cutting claim that the workflow's job dependencies form one connected
  chain (tag waits on approval, every publisher waits on tag, promotion waits
  on verification, the partial report is keyed on tag-without-promotion);
  `SVC_GW_RELEASE_0003.1` through `.7` each verify one link, narrowed to
  exactly what the existing assertions check — no new assertion was added to
  make a child's claim land.
- **Every annotation moves in the same commit**: the one `@SVCs` annotation
  on the test method now lists all eight ids; the live `release-packaging`
  OpenSpec capability spec gains the seven children as scenarios.

Observable behaviour of the gateway's release automation is **unchanged**. No
workflow file, configuration key or gate ordering changes. What the change
buys is that the traceability report now names which of the seven guarantees
regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `release-packaging`: `GW_RELEASE_0003` — *Every publicly-visible or
  irreversible release step answers to the same chain of evidence* — is now a
  parent with seven children. No behaviour delta.

## Out of scope

- **The other candidates from #299 are not touched by this change's edits.**
  `GW_VETTING_0029`, `GW_APPROVAL_0004`, `GW_VETTING_0020` and `GW_AUTH_0015`
  are handled by their own changes on the same branch; this change does not by
  itself resolve #299.
- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically.
