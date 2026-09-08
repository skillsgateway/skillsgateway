# Proposal: decompose-connector-toggle-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026`, PR #333 repeated for
`GW_INGEST_0030`, PR #334 repeated for `GW_INGEST_0024`, and this branch's own
prior commit repeated for `GW_RELEASE_0003`. `GW_VETTING_0029` — *Administrative
enable and disable of a built-in vetting connector* — is one of the five
candidates those changes left unevaluated, and this change does exactly that
one requirement and nothing else.

`GW_VETTING_0029`'s single description enumerated four distinct, independently
verifiable guarantees behind one id and one SVC:

1. a connector's effective state for a marketplace resolves per-marketplace,
   then global, then enabled;
2. a connector disabled for a snapshot's marketplace is skipped at ingestion
   or re-vetting and recorded as a distinct disabled verdict;
3. a disabled verdict counts as neither clearing nor blocking, so disabling
   every connector leaves a run blocked rather than cleared;
4. every toggle is an audited, administrator-only action.

These have different audiences and different failure modes. An operator
configuring a per-marketplace override cares about 1; a reviewer reading a
chain run cares about 2; a security reviewer worried about the switch becoming
a bypass cares about 3; an auditor cares about 4. Each can regress
independently — the aggregation rule in `VettingChain` could stay correct
while the controller quietly stopped requiring the administrative role, and
neither test would tell the traceability report which one broke.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_VETTING_0029` keeps its id and gains four children**,
  `GW_VETTING_0029.1` through `GW_VETTING_0029.4`, each linked to it by
  `references.requirement_ids`.
- **The parent's own text becomes the cross-cutting claim that is true across
  the whole set and is not any single child's**: the switch may change only
  which connectors run and how their absence is scored, never make the chain
  more permissive about what a connector's own verdicts already say — a
  disabled connector's absence is never indistinguishable from a clearing
  verdict, and disabling every connector still leaves a run blocked.
- **One parent SVC and four child SVCs.** `SVC_GW_VETTING_0029` is retargeted
  to the two `VettingChainDisabledTests` methods that guard the switch from
  becoming a bypass in a way no single child states — a disabled verdict never
  rescuing one that is genuinely failing, and every pre-existing aggregation
  case (empty run, all-pass, warned, failing) staying unchanged now that a
  fifth verdict state exists; `SVC_GW_VETTING_0029.1` through `.4` each verify
  one guarantee.
- **Every annotation moves in the same commit, and two new ones are added
  where none previously existed**: `ConnectorToggleController`,
  `ConnectorToggleRepository`, `ConnectorToggleService`, `VettingChain` and
  `VerdictState` already carried `@Requirements({"GW_VETTING_0029"})` or a
  javadoc mention that moves to the matching child; `VettingService.run` (the
  method that actually decides to skip a disabled connector) and
  `Verdict.disabled` (the factory that creates its verdict) previously carried
  only a prose mention with no `@Requirements` annotation at all — each gains
  `@Requirements({"GW_VETTING_0029.2"})` directly, the same way PR #324 added
  annotations to previously-unannotated methods where a child's guarantee
  needed one. `ConnectorToggleTests` and `VettingChainDisabledTests` have
  their `@SVCs` redistributed one guarantee per child, with the two
  bracketing tests keeping the parent SVC.

**A quirk this change works around, not fixes**: `GW_VETTING_0029` has no
entry in any live `openspec/specs/*/spec.md` — it was added by the already-merged
PR #228 via `openspec/changes/admin-vetting-override/`, a change that was never
archived. This change updates that still-open change's delta directly (adding
the four children as further `ADDED` requirements, alongside the parent it
already declares), and this change's own `specs/snapshot-vetting/spec.md`
delta declares the same four children again — the validator requires every
change to carry at least one delta of its own, and there is no live parent
entry to write a `MODIFIED` delta against. Archiving someone else's
already-merged, never-archived change is a separate cleanup and out of scope
here. Full reasoning is in this change's `design.md`.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the
change buys is that the traceability report now names which of the four
guarantees regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `snapshot-vetting`: `GW_VETTING_0029` — *The connector on/off switch never
  becomes a blanket approval* — is now a parent with four children, recorded
  in the still-open `admin-vetting-override` change's delta (see the quirk
  above). No behaviour delta.

## Out of scope

- **`GW_APPROVAL_0004` and `GW_AUTH_0015` are not touched by this change's
  edits.** They are handled by their own changes on the same branch; this
  change does not by itself resolve #299.
- **Archiving `admin-vetting-override`.** That change predates this branch and
  was already merged; archiving it is a separate housekeeping task.
- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically.
