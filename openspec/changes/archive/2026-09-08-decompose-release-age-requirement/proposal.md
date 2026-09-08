# Proposal: decompose-release-age-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026`, PR #333 for `GW_INGEST_0030`,
PR #334 for `GW_INGEST_0024`, and this branch's own prior commits for
`GW_RELEASE_0003` and `GW_VETTING_0029`. `GW_APPROVAL_0004` — *Minimum release
age before approval* — is one of the five candidates those changes left
unevaluated, and this change does exactly that one requirement and nothing
else.

`GW_APPROVAL_0004`'s single description enumerated five distinct,
independently verifiable guarantees behind one id and one SVC:

1. the age is measured from the gateway's own first-ingestion instant, never
   the upstream commit's timestamp, and is unchanged by re-ingestion;
2. the age is evaluated fresh at each approval request, gates only approval,
   and a zero setting disables it entirely;
3. an approval below the configured age is refused, naming the setting, the
   age and the time remaining;
4. the same eligibility and remaining time are reported on request, agreeing
   with the gate;
5. every approval and every age-refused approval is recorded on the ledger.

These have different audiences and different failure modes. An operator
worried about an attacker backdating a commit cares about 1; a reviewer
reading the eligibility report before deciding cares about 4; an auditor
reconstructing what a cooling-off window was worth for a given snapshot cares
about 5. Each can regress independently — the report endpoint could drift out
of sync with the actual gate, or a ledger entry could stop naming the
remaining time, without the measurement basis itself changing at all.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_APPROVAL_0004` keeps its id and gains five children**,
  `GW_APPROVAL_0004.1` through `GW_APPROVAL_0004.5`, each linked to it by
  `references.requirement_ids`.
- **The parent's own text becomes the cross-cutting claim that is true across
  the whole set and is not any single child's**: the minimum release age is a
  self-clearing control anchored to a clock the gateway alone controls, and
  every path that consults it — the approval attempt, the eligibility report,
  and the ledger entry — agrees.
- **One parent SVC and five child SVCs.** `SVC_GW_APPROVAL_0004` is retargeted
  to the one test that actually brackets that cross-cutting claim —
  `the_eligibility_endpoint_answers_what_the_gate_will_do`, which shows the
  report and a real approval attempt agree both before and after the window
  clears — rather than restating all five guarantees; `SVC_GW_APPROVAL_0004.1`
  through `.5` each verify one guarantee.
- **Every annotation moves in the same commit, and two new ones are added
  where none previously existed**: `ReleaseAgeGate`'s package-private pure
  function (the boundary-precision rule itself) and
  `ApprovalService.requireReleaseAge` (the method that actually raises the
  refusal and appends it to the ledger) previously carried no
  `@Requirements` annotation at all — only a javadoc mention of the bare
  parent id, or none. Both now carry a direct annotation, the same way PR #324
  and this branch's `GW_VETTING_0029` change added annotations where a
  child's guarantee needed a real implementation site. `ReleaseAgeGateTests`,
  `MinimumReleaseAgeTests`, and the frontend's `useSnapshotReleaseAge` hook and
  its consuming `ApproveDialog` component have their annotations
  redistributed one guarantee per child.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the
change buys is that the traceability report now names which of the five
guarantees regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `snapshot-approval`: `GW_APPROVAL_0004` — *The minimum release age is a
  self-clearing control anchored to the gateway's own clock* — is now a
  parent with five children. No behaviour delta.

## Out of scope

- **`GW_AUTH_0015` is not touched by this change's edits.** It is handled by
  its own change on the same branch; this change does not by itself resolve
  #299.
- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically.
