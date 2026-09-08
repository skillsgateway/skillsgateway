# Design: decompose-gated-release-requirement

## Context

- `GW_RELEASE_0003` — *Gated release automation* — was 1105 characters,
  the fourth-longest candidate #299 listed, and `implementation: configuration`:
  it describes `.github/workflows/release.yml`, verified by one structural
  JUnit test (`PackagingTests.
  releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing`) that
  parses the workflow YAML and asserts against its shape — not a runtime test
  of gateway code.
- Unlike the three prior decompositions on this branch, this requirement has
  **no separate test methods to redistribute**: every guarantee is checked by
  one method's independent assertions. The mechanics still apply — one child
  per independently-verifiable guarantee, references back to the parent, a
  parent SVC retargeted to a cross-cutting claim — but the "move an
  annotation from method A to method B" step collapses to "the one method
  keeps all eight SVC ids", because it is the one method that verifies all
  eight.

## Decision 1 — one child per pipeline link, not one per named `shall`

The description lists eight `shall` clauses. Two collapse into one child each
because they are one link stated in two sentences: "cut a release only from a
manual dispatch" and "default to a preview" are both about how a run starts
before anything is committed to, so they are `GW_RELEASE_0003.1` together, not
two children over one dispatch input. Everything else maps one clause to one
child:

| Child | Link |
| --- | --- |
| `GW_RELEASE_0003.1` | manual dispatch only, preview by default |
| `GW_RELEASE_0003.2` | override flag required for a disagreeing hand-entered version |
| `GW_RELEASE_0003.3` | release-candidate mode offered alongside a plain release |
| `GW_RELEASE_0003.4` | main-branch gates, then a required reviewer's approval, before the tag and every publisher |
| `GW_RELEASE_0003.5` | created as a prerelease |
| `GW_RELEASE_0003.6` | promotion waits on published-artifact verification, tolerating an expected skip |
| `GW_RELEASE_0003.7` | a run that tags without promoting reports what survived it |

Why not finer on `.4`: "runs the main-branch gates", "holds for a required
reviewer" and "the tag and every publisher wait on that approval" are three
sentences in the original text but one behaviour under test — the same
`needsOf`/`environment` assertions establish all three in the same breath, and
splitting them would produce three children whose tests could not regress
independently of each other in practice (the approval job's `needs` list is
one YAML array; a change to it either breaks the whole ordering or none of
it).

## Decision 2 — a claim removed rather than overclaimed: release-candidate promotion

The pre-decomposition description said the release-candidate mode is
"verified like any release but never promoted". The workflow does implement
"never promoted" — `promote`'s `with.prerelease` input is
`${{ needs.prepare.outputs.prerelease == 'true' }}`, which the shared
`common-release-promote.yml` workflow uses to leave a candidate a prerelease
permanently — but **no existing assertion in `PackagingTests` checks that
line**. The only assertion touching the `prerelease` input checks that the
dispatch dropdown offers `[none, rc, b, a]`.

Rather than write `GW_RELEASE_0003.3`'s SVC to claim more than that one
assertion proves — which the project's convention (PR #324's "claims the
decomposition removed") treats as a defect to fix, not a detail to gloss —
`GW_RELEASE_0003.3` and `SVC_GW_RELEASE_0003.3` state only that the mode is
offered. The "never promoted" behaviour is real and still described in the
requirement text as something the workflow does, but the gap between what is
implemented and what is asserted is now visible at the child level instead of
buried inside a seven-clause paragraph's aggregate SVC. Closing it — adding an
assertion on `promote`'s `with.prerelease` line — is a candidate follow-up,
not part of this change: it would be a new test, and this change moves
annotations only.

## Decision 3 — the parent SVC verifies the dependency graph, not an eighth link

`GW_RELEASE_0003`'s cross-cutting claim is that the seven links form one
continuous chain rather than seven independent steps that happen to run in
the right order. That is a real, distinct thing to check: a workflow could
implement every individual gate correctly and still have a job wired to skip
past one of them (for instance, a publisher whose `needs` list is missing
`tag`). `SVC_GW_RELEASE_0003` is retargeted to the `needsOf` assertions that
establish the graph itself — tag depends on approve, every publisher depends
on tag, promote depends on verify, and the partial-report job's own condition
is keyed on tag-succeeded-but-promote-not — rather than restating any single
link. This is the same test method as every child, for the mechanical reason
PR #324, #333 and #334 all found: reqstool resolves `@SVCs` to a JUnit
`Class.method`, so there was never a class-level option here either, and in
this case there is only the one method to put any of the nine SVC ids on.

This was considered and rejected: giving `GW_RELEASE_0003` no SVC of its own,
since the "one chain" claim is a restatement of what the seven children
already assert individually. Rejected for the same reason PR #333 and #334
rejected it — reqstool requires every requirement, parent included, to carry
at least one SVC — and because "the graph connects them" is not, in fact,
implied by any single child holding: seven correctly-implemented gates wired
into two disconnected chains would pass every child and fail the parent.

## The other candidate inspected in this branch: GW_VETTING_0020

`GW_VETTING_0020` — *License allow and ban lists through the standard vetting
path* (899 chars) — was read for this branch and judged to **stay
monolithic**, for a different reason than `GW_INGEST_0025`'s counter-example.

Its description has two parts. The first — a license is evaluated against a
ban list, and, when configured, an allow list, defaulting to informational and
warning severities with neither configured — is one decision matrix
implemented by two methods on one class, `LicensePolicy.evaluate` and
`LicensePolicy.findings`; the ban-list, allow-list and default cases are
branches of a single function, not three behaviours, and splitting them would
fragment one conditional the way splitting `GW_INGEST_0026.4`'s ratio from its
floor would have.

The second part — "every such finding shall flow through the standard vetting
chain, so that it aggregates fail-closed, ... is acceptable only by a scoped
expiring waiver, ... is re-examined by continuous re-vetting, and lands on the
append-only ledger" — reads like a bundle of separate guarantees, but it is
not independently verifiable *for this connector*: fail-closed aggregation is
already `GW_VETTING_0002`, waiver acceptance and continuous re-vetting are
already their own requirements, and none of them is retested here — the
license connector's findings are ordinary `Finding` objects with no special
casing anywhere in the chain, so a regression in any of those properties would
already be caught by their own requirement's own SVC regardless of what this
connector does. Making it a child would create a requirement whose only
possible test is "run the existing generic chain test again, with a license
fixture", which does not answer a question the generic test does not already
answer. The one clause left over, "the connector's recorded version shall
reflect the policy in force", is a single thin attributability guarantee with
no dedicated method of its own — the same shape as `GW_INGEST_0025`'s
timeout clause, not worth the annotation churn of a lone child.

`GW_VETTING_0020` is left as it is. No requirements.yml, SVC or annotation
change was made for it.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight work
  on this same branch appends; resolved by ordering edits sequentially and
  running the independent id check after each.
- **No new top-level id was minted.** This change consumes no `GW_RELEASE_NNNN`
  number and cannot collide with a reserved range.
