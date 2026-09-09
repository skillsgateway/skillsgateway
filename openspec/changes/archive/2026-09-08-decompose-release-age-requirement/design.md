# Design: decompose-release-age-requirement

## Context

- `GW_APPROVAL_0004` — *Minimum release age before approval* — was 943
  characters, the sixth-longest candidate #299 listed.
- Its implementation is small and concentrated: `ReleaseAgeGate` (the rule
  itself, as an instance method reading the snapshot and a package-private
  pure function of three inputs), `ApprovalService` (the orchestration point
  and the private method that raises the refusal and audits it), and
  `AdminController` (the reporting endpoint and the exception handler that
  formats the refusal response).
- Two test classes carry `SVC_GW_APPROVAL_0004`: `ReleaseAgeGateTests` (a
  pure-function suite over the boundary instant, three methods) and
  `MinimumReleaseAgeTests` (an integration-level suite over a real approval
  flow, six methods).

## Decision 1 — five children, grouped by audience rather than by clause

The description reads as eight `shall` clauses. Grouped by who reads the
guarantee and what breaks if it regresses:

| Child | Guarantee | Audience |
| --- | --- | --- |
| `GW_APPROVAL_0004.1` | measured from the gateway's own first sighting, immune to backdating and re-ingestion | an operator defending against a backdated commit |
| `GW_APPROVAL_0004.2` | evaluated fresh per request, gates only approval, zero disables it | the rule's own timing and scope |
| `GW_APPROVAL_0004.3` | refused with a conflict naming the setting, age and remaining time | a reviewer attempting a blocked approval |
| `GW_APPROVAL_0004.4` | the same numbers reported on request | a reviewer checking before deciding |
| `GW_APPROVAL_0004.5` | recorded on the ledger, both allowed and refused | an auditor reconstructing what the window was worth |

Two clauses collapse into `.2` that read as separate sentences in the original
text — "evaluated at the instant of each approval request" and "rejection is
never gated" and "zero disables the gate" — because all three are properties
of the *same* pure function (`ReleaseAgeGate`'s package-private `evaluate`)
answering "does the rule apply, and what does it say right now", rather than
three different behaviours. Splitting them further would produce children
whose tests could not regress independently of each other: the zero-check and
the boundary check are two assertions inside the same function's two branches.

## Decision 2 — the parent SVC brackets "the numbers agree", not a sixth guarantee

The parent's cross-cutting claim is that the age gate is trustworthy exactly
because every surface that consults it — the approval attempt, the report, the
ledger — computes from the same rule and therefore can never disagree.
`MinimumReleaseAgeTests.the_eligibility_endpoint_answers_what_the_gate_will_do`
is the one test that actually demonstrates this: it reads the report while
ineligible, provokes a real refusal and checks the refusal's numbers match the
report's, then ages the snapshot and reads the report again to confirm it
agrees the window has cleared. No single child states this — `.3` is about the
refusal alone, `.4` is about the report alone — so `SVC_GW_APPROVAL_0004` is
retargeted to this one method, following the same mechanical rule PR #324,
#333, #334, and this branch's two prior changes all used: reqstool resolves
`@SVCs` to a JUnit `Class.method`, so the parent SVC sits on a method, never on
the class.

## Decision 3 — two new `@Requirements` annotations

As with `GW_VETTING_0029` on this branch, decomposing this requirement
surfaced two implementation sites that were genuinely tested but carried no
`@Requirements` annotation of their own:

- `ReleaseAgeGate`'s package-private `evaluate(long, Instant, Instant,
  Duration)` — the pure function `ReleaseAgeGateTests` exercises directly at
  the boundary instant and at zero — had no annotation; only the public
  instance overload that calls it did. It now carries
  `@Requirements({"GW_APPROVAL_0004.2"})` directly.
- `ApprovalService.requireReleaseAge` — the private method that calls
  `ReleaseAgeGate.require`, catches the refusal, and appends it to the
  ledger — carried a javadoc paragraph naming the bare parent id but no
  `@Requirements` annotation at all. It now carries
  `@Requirements({"GW_APPROVAL_0004.3", "GW_APPROVAL_0004.5"})`.

**This is the third time on this branch that a gap like this surfaced only
once `reqstool status` was run against the decomposed ids** (the first was
`GW_RELEASE_0003.5`'s missing `implementation: configuration`, the second was
`GW_VETTING_0029`'s missing bare-parent annotation after every site moved to a
child). A method that orchestrates a well-tested private helper can carry the
requirement's annotation while the helper itself carries none, and nothing
before decomposition made that gap visible — the single multi-clause id was
"implemented" as long as any one method carrying it existed anywhere.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight work
  on this same branch appends; resolved by ordering edits sequentially and
  running the independent id check after each.
- **No new top-level id was minted.** This change consumes no
  `GW_APPROVAL_NNNN` number and cannot collide with a reserved range.
