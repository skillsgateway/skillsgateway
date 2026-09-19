# Proposal: administrative-revocation

## Why

A supply-chain compromise is discovered in content the gateway has already
approved and is serving. An administrator has no way to withdraw it.

`SnapshotRepository.revoke` exists and works, but its only caller is
`RevetService`: a snapshot becomes revoked solely as a *side effect* of a
re-vetting run whose chain now blocks it, and only when
`skills-gateway.revet.mode` is `ENFORCE` — which is not the default. Nothing
else in the system can revoke. So the path available today is to write a vetter
that fails the specific SHA, switch the estate into enforce mode, and re-vet —
a code or configuration change made under incident pressure.

The other levers do not reach:

- **Policy deny rules cannot retract.** `PolicyGate.enforce` is called from
  exactly one place, `ApprovalService`, at approval time. A new rule matching the
  bad content does nothing to content already approved.
- **Retention cannot touch it.** An approved snapshot is an absolute guard,
  never eligible by policy or by hand.

The machinery for the withdrawal itself is almost all present — the ledger
event, the webhook, `GitStorage.unpublish`, mirror reconciliation. What is
missing is a door, a decision, and durability.

## What Changes

### 1. A direct administrative revoke

`POST /api/snapshots/{id}/revoke`, admin-only, with a mandatory non-empty stated
reason. It revokes an approved snapshot **whatever the vetting chain currently
says**, because the reason is human knowledge the chain does not have: a CVE
disclosure, a maintainer compromise, a dependency found to be malicious. It does
not require `revet.mode=ENFORCE`; the mode governs what *automated* re-vetting
does with its own findings and has no bearing on an administrator's decision.

The decision is recorded on the append-only ledger with the acting identity and
the reason, `marketplace.snapshot.revoked` is emitted, and the content is
unpublished through the same storage seam approval publishes through, so the
facade stops serving it and mirror reconciliation withdraws it from the forge.

**No four-eyes.** Approval has one because publishing is the direction that can
do harm; revocation only ever withdraws, so a second identity buys no safety and
costs time during a live incident. The mandatory reason and the ledger entry are
the accountability. Stated here because it is a deliberate asymmetry with
`GW_APPROVAL_0010 — Separation of duties on snapshot approval`, not an oversight.

### 2. What is served afterwards is a stated choice

Revoking the snapshot a marketplace is currently serving otherwise takes that
marketplace off the air as a side effect nobody chose. The request must say what
happens next, and **a request that does not is refused**:

- **roll back** to the previous approved snapshot of that marketplace, or
- **serve nothing**.

Neither is a safe default. Rolling back silently republishes older content that
may carry the same compromise — a poisoned transitive dependency is usually in
the previous snapshot too. Serving nothing takes a marketplace down, which for a
marketplace with an obviously clean predecessor is an unnecessary outage. The
caller knows which; the gateway does not.

A rollback **is a publication** and is recorded as one, distinctly from the
revocation, so the ledger shows both acts rather than one act with a hidden
second effect. Asking to roll back when there is no earlier approved snapshot is
refused before anything is revoked, rather than silently degrading to serving
nothing.

### 3. The withdrawal outlives the incident

Two things could undo a revocation quietly, and neither is the one I first
assumed.

**Not ingestion.** `IngestionService` already returns the existing snapshot row
for a `(marketplace, sha)` whatever state it is in, so a revoked commit does not
reappear as held on the next sync. That block exists; nothing needs building.

**Approval, which can.** A revoked snapshot can be approved again today —
`GW_VETTING_0013 — Auto-quarantine of a violating approved snapshot` requires
exactly that, because a re-vetting revocation must be liftable by waiving the
finding that caused it. For an administrative revocation there *is* no finding
to waive: the chain already clears the content, and the objection lives only in
a person's head. So an ordinary approval must refuse it, naming the revocation,
its reason, who made it and when.

**Retention, which is worse.** `revoked` is a deletable state and purge is a
hard delete, so a retention sweep can remove the row the refusal is derived
from. After that the same upstream commit ingests as an ordinary held snapshot
carrying no trace of anything, and a reviewer approves it in good faith. This is
the quieter laundering path — nothing refuses and nothing is recorded — and it
is closed by never permanently removing an administratively revoked record,
while still reclaiming its content.

**And a reversal, because permanence is the wrong remedy.** An administrator's
knowledge can be wrong or overtaken: a snapshot withdrawn by mistake during an
incident, or a compromise that lay in an upstream dependency since fixed. So the
block lifts — but only when an administrator other than the one who set it says
so on a stated reason. Revoking needs one identity because withdrawing cannot
publish; reversing publishes, so it earns a second. The reversal names the
revocation on the ledger and leaves a marker on the snapshot, so content served
over a reversed withdrawal is never indistinguishable from content nobody ever
withdrew.

Permanence would have been weaker than it looks in any case: an administrator
who wanted the content served could commit on top of the withdrawn one and
approve the resulting commit. What is worth buying is not prevention but a
record.

**This is derived from the snapshot row**, not a denylist table — with one
column added to record *which kind* of revocation it was, because the two lift
differently. No new estate object type, and a derived answer cannot drift from
the revocation it describes.

## Capabilities

### New Capabilities

None. Revocation belongs to the existing approval and retention capabilities.

### Modified Capabilities

- `snapshot-approval`: gains administrative revocation as a first-class act
  (`GW_APPROVAL_0015`), the served-content choice that accompanies it
  (`GW_APPROVAL_0016`), and the approval-side refusal of a revoked commit
  (`GW_APPROVAL_0017`).
- `snapshot-retention`: an administratively revoked record is never permanently
  removed, though its content is still reclaimed (`GW_RETENTION_0008`).

## Impact

- **API**: one new endpoint. Additive within the major, per
  `docs/manual/reference/compatibility.md`.
- **Backend**: a revocation service beside `ApprovalService` reusing its storage
  seam and repair discipline; a gate in `doApprove`; a reversal carried on the
  existing `ApprovalOverride` request field, the shape
  `GW_VETTING_0028 — Administrative override of a blocked vetting outcome`
  already established; a retention guard.
- **Schema**: one column on `snapshots` recording the revocation's kind —
  re-vetting or administrative — because the two lift differently. Folded into
  `V1__init.sql`, per the pre-1.0 convention.
- **Trust boundary.** This writes to the served set and adds a gate at approval.
  `.claude/skills/old-coder` discipline applies: adversarial and negative tests,
  not happy-path coverage.
- **Declarative estate (#65) does not extend.** A revocation is an *act*, not
  converge-able state — the same line `ADR 0014 — Estate export` drew for
  exports. `skills-gateway.estate.*` gains nothing, and reconciling a converged
  estate must not re-revoke anything.
- **Docs**: the REST API reference, and the approving-snapshots guide gains
  the withdrawal half of the lifecycle.
- **Out of scope**: telling clients that already hold the content
  ([#427](https://github.com/skillsgateway/skillsgateway/issues/427)). This
  change makes the gateway stop serving it; making a client notice is a separate
  problem with its own design.
