# Proposal: chain-staleness-at-the-approval-gate

## Why

Enabling a vetter re-runs nothing. That is defensible for content already
approved — the re-vetting sweep (GW_VETTING_0012 — Continuous re-vetting of
approved snapshots) converges on it — but it is not defensible at the approval
gate, which is the control the whole architecture rests on.

`ApprovalService` gates on the snapshot's *latest stored* chain run. So:

> Enable the secret scanner today. Approve a snapshot ingested yesterday. It is
> approved against the chain as it was yesterday, and nothing in the system
> notices.

The vetter an administrator deliberately turned on did not look at the content,
and the approval records no sign of it. The defect is the **silence**, not the
staleness: a reviewer who knows the evidence predates the current chain can
decide; one who is never told cannot.

Issue [#457](https://github.com/skillsgateway/skillsgateway/issues/457) sets out
four options and chooses none.

## What Changes

- **Chain staleness is computed and stated.** `GET /api/v1/snapshots/{id}/vetting`
  reports whether the snapshot's latest run was produced by the chain the
  marketplace runs now, naming both identities when they differ. Every run is
  already stamped with `VettingService.chainIdentity(marketplaceId)`, and the
  toggles that identity deliberately does not capture are recorded on the run as
  `disabled` verdicts, so the comparison is over data that already exists.
- **The approval record carries it.** An approval of a snapshot whose evidence
  predates the current chain says so in the audit ledger, naming the chain that
  produced the evidence and the chain in force at the moment of approval. The
  approval still proceeds.
- **A held snapshot's chain can be re-run**, so the marking is actionable. Today
  `RevetService` refuses anything not approved, which leaves a reviewer told
  their evidence is stale with no way to refresh it short of rejecting and
  re-ingesting.
- **The portal states it** on the vetting report, where the reviewer is already
  reading the verdicts that gate the decision, with the control to refresh.
- Requirement GW_VETTING_0038 — Chain staleness is stated at the approval gate,
  with SVC_GW_VETTING_0038.

### The decision this change takes, which the issue left open

The issue's four options are: re-vet at approval; refuse approval; **mark it**;
or re-vet the held set when a chain setting changes. This change takes the
third, which is the owner's recorded weak preference, on these grounds:

- It removes the silence, which the issue itself identifies as the actual defect.
- It is consistent with how this system treats every other accepted risk:
  overrides and waivers state the fact and record who accepted it, rather than
  refusing on the gateway's own judgment.
- Refusing would turn one chain change into a queue of manual steps across the
  whole held set, and the gateway cannot know whether that is proportionate.
- Re-running at approval time makes approval slower and able to fail for a new
  reason, and hides work inside the one operation that must be predictable.
- Re-vetting the held set on a configuration write is work triggered by a
  setting, which is the shape the stop rule asks hard questions about.

**Deliberately not added: a mode.** The `revet.mode` warn/enforce split is the
precedent for having both, and the issue names it. It is not followed here: a
mode is a new configuration leaf, the fact is the same either way, and enforcing
can be built on this fact later if the owner wants it. Shipping the marking first
costs nothing if the answer turns out to be "also block".

**This is a decision for the owner to overturn at review**, not a settled
question. It is recorded here, and in the PR body, for exactly that reason.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-vetting`: the vetting read for a snapshot reports whether its latest
  chain run was produced by the chain the marketplace runs now, and a snapshot
  that is not yet approved can have its chain re-run.
- `snapshot-approval`: an approval whose evidence was produced by a different
  chain than the one in force records that fact, naming both chains. The
  approval is not refused.

## Impact

- **DB**: none. `vetting_runs.chain` already records the chain identity.
- **Backend**: `VettingController` (+staleness in `VettingView`),
  `ApprovalService` (+the ledger detail), `RevetService` (a held snapshot's
  chain can be re-run), `VettingService` (an accessor for the current identity
  where it is needed). No new package.
- **API**: additive within the major — one field on an existing read, and an
  existing endpoint accepting a state it used to refuse. `openapi.json`
  regenerated.
- **Configuration**: no new settable leaves — `ConfigSurfaceBudgetTests` is
  unmoved. No new Spring test context — `ContextBudgetTests` is unmoved.
- **Trust boundary**: yes — `ApprovalService`. The `.claude/skills/old-coder`
  discipline applies: negative and adversarial tests, and every test proven to
  fail before it passes.
- **Stop rule**: no new estate object, grantable role, scheduled sweep,
  configuration leaf or backend package. The capability map absorbs this in the
  rows it already has: Snapshot approval and Vetting chain both keep their
  descriptions, because this states a fact about evidence rather than adding a
  capability.
- **Docs** (same PR): `reference/portal.md`, `reference/api/marketplaces.md`,
  `guides/approving-snapshots.md`, `guides/vetting.md` if it describes chain
  changes.
